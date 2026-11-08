package com.taca.cart.integrations;

import com.taca.cart.application.ProductHydrationResult;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.config.ProductProperties;
import com.taca.cart.observability.CartDependencyMetrics;
import com.taca.cart.observability.CartDependencyMetrics.ProductHttpOutcome;
import com.taca.cart.observability.OutboundHttpTracing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class HttpProductHydrationClient implements ProductHydrationClient {
    private static final String PRODUCT_PATH = "/api/v1/products/";
    private static final long MAX_PRICE = 999999999999L;

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final URI baseUri;
    private final Duration timeout;
    private final CartDependencyMetrics metrics;
    private final OutboundHttpTracing tracing;

    public HttpProductHydrationClient(
            ProductProperties properties,
            String baseUrl) {
        this(properties, baseUrl, null, null);
    }

    public HttpProductHydrationClient(
            ProductProperties properties,
            String baseUrl,
            CartDependencyMetrics metrics) {
        this(properties, baseUrl, metrics, null);
    }

    @Autowired
    public HttpProductHydrationClient(
            ProductProperties properties,
            @Value("${product.base-url:}") String baseUrl,
            CartDependencyMetrics metrics,
            OutboundHttpTracing tracing) {
        this.timeout = properties.timeout();
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Product timeout must be positive");
        }

        this.baseUri = parseBaseUri(baseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.jsonMapper = JsonMapper.builder().build();
        this.metrics = metrics;
        this.tracing = tracing;
    }


    @Override
    public ProductHydrationResult fetchProduct(String productId) {
        if (!validId(productId) || baseUri == null) {
            return ProductHydrationResult.stale();
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(
                        baseUri.resolve(PRODUCT_PATH + productId))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();

        long startedAt = System.nanoTime();
        ProductHttpOutcome outcome = ProductHttpOutcome.IO_ERROR;

        try {
            HttpResponse<String> response = tracing == null
                    ? httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    : tracing.send(httpClient, requestBuilder,
                    "product.catalog.get", "/api/v1/products/{productId}");

            if (response.statusCode() == 404) {
                outcome = ProductHttpOutcome.NOT_FOUND;
                return ProductHydrationResult.unavailable();
            }

            if (response.statusCode() != 200) {
                outcome = ProductHttpOutcome.HTTP_ERROR;
                return ProductHydrationResult.stale();
            }

            ProductHydrationResult result = parseProduct(response.body(), productId);

            outcome = switch (result.state()) {
                case FOUND -> ProductHttpOutcome.FOUND;
                case UNAVAILABLE -> ProductHttpOutcome.UNAVAILABLE;
                case STALE -> ProductHttpOutcome.INVALID_RESPONSE;
            };

            return result;
        } catch (HttpTimeoutException ex) {
            outcome = ProductHttpOutcome.TIMEOUT;
            return ProductHydrationResult.stale();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            outcome = ProductHttpOutcome.INTERRUPTED;
            return ProductHydrationResult.stale();
        } catch (IOException ex) {
            outcome = ProductHttpOutcome.IO_ERROR;
            return ProductHydrationResult.stale();
        } finally {
            if (metrics != null) {
                metrics.recordProductHttp(outcome, System.nanoTime() - startedAt);
            }
        }
    }

    private ProductHydrationResult parseProduct(String body, String productId) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode data = child(root, "data");

            if (data == null || !data.isObject()) {
                return ProductHydrationResult.stale();
            }

            if (!productId.equals(text(data, "product_id"))) {
                return ProductHydrationResult.stale();
            }

            String status = text(data, "status");
            if (status == null) {
                return ProductHydrationResult.stale();
            }

            if (!"ACTIVE".equals(status)) {
                return ProductHydrationResult.unavailable();
            }

            String title = text(data, "title");
            String shopId = text(child(data, "shop"), "shop_id");
            JsonNode skus = child(data, "skus");

            if (title == null || title.isBlank() || title.length() > 256
                    || !validId(shopId) || skus == null || !skus.isArray()) {
                return ProductHydrationResult.stale();
            }

            Map<String, ValidatedSkuSnapshot> snapshots = new HashMap<>();

            for (JsonNode skuNode : skus) {
                if (!skuNode.isObject()) {
                    return ProductHydrationResult.stale();
                }

                String skuId = text(skuNode, "sku_id");
                if (!validId(skuId)) {
                    return ProductHydrationResult.stale();
                }

                String skuStatus = text(skuNode, "status");
                if (skuStatus == null) {
                    JsonNode statusNode = child(skuNode, "status");
                    if (statusNode != null && !statusNode.isNull()) {
                        return ProductHydrationResult.stale();
                    }
                }

                if (skuStatus != null && !"ACTIVE".equals(skuStatus)) {
                    continue;
                }

                JsonNode price = child(skuNode, "price");
                JsonNode salePrice = child(price, "sale_price");

                if (price == null || !price.isObject()
                        || !"VND".equals(text(price, "currency"))
                        || salePrice == null || !salePrice.isIntegralNumber()) {
                    return ProductHydrationResult.stale();
                }

                long amount = new BigDecimal(salePrice.asText()).longValueExact();
                if (amount < 1 || amount > MAX_PRICE) {
                    return ProductHydrationResult.stale();
                }

                String label = buildSkuLabel(child(skuNode, "attributes"));

                ValidatedSkuSnapshot snapshot = new ValidatedSkuSnapshot(
                        productId, skuId, shopId, title, label, amount);

                if (snapshots.putIfAbsent(skuId, snapshot) != null) {
                    return ProductHydrationResult.stale();
                }
            }

            return ProductHydrationResult.found(snapshots);
        } catch (Exception ex) {
            return ProductHydrationResult.stale();
        }
    }

    private String buildSkuLabel(JsonNode attributes) {
        if (attributes == null || attributes.isNull()) return "";
        if (!attributes.isObject()) {
            throw new IllegalArgumentException("Invalid SKU attributes");
        }

        String label = attributes.properties().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + ": " + attributeValue(entry.getValue()))
                .collect(Collectors.joining(" / "));

        return label.length() <= 128 ? label : label.substring(0, 128);
    }

    private String attributeValue(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        if (value.isNumber() || value.isBoolean()) return value.toString();
        throw new IllegalArgumentException("Invalid attribute value");
    }

    private static JsonNode child(JsonNode parent, String name) {
        return parent == null ? null : parent.get(name);
    }

    private static String text(JsonNode parent, String name) {
        JsonNode value = child(parent, name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static URI parseBaseUri(String value) {
        if (value == null || value.isBlank()) return null;

        URI uri = URI.create(value.trim());
        boolean validScheme = "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());

        if (!validScheme || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Invalid Product Catalog base URL");
        }

        return uri;
    }
}

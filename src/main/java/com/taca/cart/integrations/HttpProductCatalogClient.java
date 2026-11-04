package com.taca.cart.integrations;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.config.ProductProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class HttpProductCatalogClient implements ProductCatalogClient {
    private static final String PRODUCT_PATH = "/api/v1/products/";
    private static final long MAX_PRICE = 999999999999L;

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final URI baseUri;
    private final Duration timeout;

    public HttpProductCatalogClient(ProductProperties properties,
                                    @Value("${product.base-url:}") String baseUrl) {
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
    }

    @Override
    public ValidatedSkuSnapshot validateSku(String productId, String skuId) {
        if (!validId(productId) || !validId(skuId)) {
            throw new CartException(CartErrorCode.CART_INVALID_INPUT, "Invalid product or SKU ID");
        }
        if (baseUri == null) throw dependencyUnavailable();

        URI uri = baseUri.resolve(PRODUCT_PATH + productId);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw dependencyUnavailable();
        } catch (IOException ex) {
            throw dependencyUnavailable();
        }

        if (response.statusCode() == 404) throw productUnavailable();
        if (response.statusCode() != 200) throw dependencyUnavailable();

        JsonNode root;
        try {
            root = jsonMapper.readTree(response.body());
        } catch (Exception ex) {
            throw dependencyUnavailable();
        }

        return validateProductDetail(root, productId, skuId);
    }

    private ValidatedSkuSnapshot validateProductDetail(JsonNode root, String productId, String skuId) {
        JsonNode data = child(root, "data");
        if (data == null || !data.isObject()) throw dependencyUnavailable();

        String returnedProductId = text(data, "product_id");
        String status = text(data, "status");

        if (!productId.equals(returnedProductId)) throw dependencyUnavailable();
        if (status == null) throw dependencyUnavailable();
        if (!"ACTIVE".equals(status)) throw productUnavailable();

        JsonNode skus = child(data, "skus");
        if (skus == null || !skus.isArray()) throw dependencyUnavailable();

        JsonNode selected = null;
        for (JsonNode sku : skus) {
            if (!skuId.equals(text(sku, "sku_id"))) continue;
            if (selected != null) throw dependencyUnavailable();
            selected = sku;
        }

        if (selected == null) throw productUnavailable();

        String skuStatus = text(selected, "status");
        if (skuStatus != null && !"ACTIVE".equals(skuStatus)) throw productUnavailable();

        JsonNode shop = child(data, "shop");
        String shopId = text(shop, "shop_id");
        String title = text(data, "title");
        JsonNode price = child(selected, "price");
        JsonNode salePrice = child(price, "sale_price");

        if (!validId(shopId) || title == null || title.isBlank()
                || price == null || !"VND".equals(text(price, "currency"))
                || salePrice == null || !salePrice.isIntegralNumber()) {
            throw dependencyUnavailable();
        }

        long amount;
        try {
            amount = salePrice.longValue();
        } catch (RuntimeException ex) {
            throw dependencyUnavailable();
        }
        if (amount < 1 || amount > MAX_PRICE) throw dependencyUnavailable();

        String skuLabel = buildSkuLabel(child(selected, "attributes"));

        try {
            return new ValidatedSkuSnapshot(
                    productId, skuId, shopId, title, skuLabel, amount);
        } catch (IllegalArgumentException ex) {
            throw dependencyUnavailable();
        }
    }

    private String buildSkuLabel(JsonNode attributes) {
        if (attributes == null || attributes.isNull()) return "";
        if (!attributes.isObject()) throw dependencyUnavailable();

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
        throw dependencyUnavailable();
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

        if (!validScheme || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Invalid Product Catalog base URL");
        }

        return uri;
    }

    private static CartException productUnavailable() {
        return new CartException(
                CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                "Product or SKU is unavailable");
    }

    private static CartException dependencyUnavailable() {
        return new CartException(
                CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                "Product Catalog is unavailable");
    }
}

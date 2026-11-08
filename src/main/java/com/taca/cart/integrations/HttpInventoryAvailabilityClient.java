package com.taca.cart.integrations;

import com.taca.cart.application.InventoryStock;
import com.taca.cart.config.InventoryProperties;
import com.taca.cart.observability.CartDependencyMetrics;
import com.taca.cart.observability.CartDependencyMetrics.InventoryHttpOutcome;
import com.taca.cart.observability.CartDependencyMetrics.InventorySkipReason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

@Component
public class HttpInventoryAvailabilityClient implements InventoryAvailabilityClient {
    private static final String PATH = "/internal/inventory/availability";
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final URI baseUri;
    private final String internalToken;
    private final InventoryProperties properties;
    private final Semaphore permits;
    private final CartDependencyMetrics metrics;


    public HttpInventoryAvailabilityClient(
            InventoryProperties properties,
            String baseUrl,
            String internalToken) {
        this(properties, baseUrl, internalToken, null);
    }

    @Autowired
    public HttpInventoryAvailabilityClient(
            InventoryProperties properties,
            @Value("${inventory.base-url:}") String baseUrl,
            @Value("${inventory.internal-token:}") String internalToken,
            CartDependencyMetrics metrics) {
        this.properties = properties;
        this.baseUri = parseBaseUri(baseUrl);
        this.internalToken = internalToken;
        this.permits = new Semaphore(properties.maxConcurrency(), true);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.jsonMapper = JsonMapper.builder().build();
        this.metrics = metrics;
    }


    @Override
    public Map<String, InventoryStock> fetchAvailability(Set<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            recordSkipped(InventorySkipReason.EMPTY);
            return Map.of();
        }

        if (skuIds.size() > properties.maxBatchSize()
                || skuIds.stream().anyMatch(id -> !validId(id))) {
            recordSkipped(InventorySkipReason.INVALID);
            return Map.of();
        }

        if (baseUri == null || internalToken == null || internalToken.isBlank()) {
            recordSkipped(InventorySkipReason.NOT_CONFIGURED);
            return Map.of();
        }

        if (!permits.tryAcquire()) {
            recordSkipped(InventorySkipReason.BUSY);
            return Map.of();
        }

        long startedAt = System.nanoTime();
        InventoryHttpOutcome outcome = InventoryHttpOutcome.CLIENT_ERROR;

        try {
            List<String> ordered = skuIds.stream().sorted().toList();
            String query = URLEncoder.encode(
                    String.join(",", ordered), StandardCharsets.UTF_8);
            URI uri = baseUri.resolve(PATH + "?sku_ids=" + query);

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.timeout())
                    .header("Accept", "application/json")
                    .header("X-Internal-Service-Token", internalToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                outcome = InventoryHttpOutcome.HTTP_ERROR;
                return Map.of();
            }

            Map<String, InventoryStock> result =
                    parseResponse(response.body(), skuIds);

            if (result.isEmpty()) {
                outcome = InventoryHttpOutcome.EMPTY;
            } else if (result.size() < skuIds.size()) {
                outcome = InventoryHttpOutcome.PARTIAL;
            } else {
                outcome = InventoryHttpOutcome.COMPLETE;
            }

            return result;
        } catch (HttpTimeoutException ex) {
            outcome = InventoryHttpOutcome.TIMEOUT;
            return Map.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            outcome = InventoryHttpOutcome.INTERRUPTED;
            return Map.of();
        } catch (IOException ex) {
            outcome = InventoryHttpOutcome.IO_ERROR;
            return Map.of();
        } catch (RuntimeException ex) {
            outcome = InventoryHttpOutcome.CLIENT_ERROR;
            return Map.of();
        } finally {
            permits.release();
            if (metrics != null) {
                metrics.recordInventoryHttp(outcome, System.nanoTime() - startedAt);
            }
        }
    }

    private void recordSkipped(InventorySkipReason reason) {
        if (metrics != null) metrics.recordInventorySkipped(reason);
    }

    private Map<String, InventoryStock> parseResponse(String body, Set<String> requested) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode data = child(root, "data");
            if (data == null || !data.isArray()) return Map.of();

            Map<String, InventoryStock> result = new HashMap<>();

            for (JsonNode item : data) {
                String skuId = text(item, "sku_id");
                String status = text(item, "status");
                String asOf = text(item, "as_of");
                JsonNode qtyNode = child(item, "available_qty");

                if (!validId(skuId) || !requested.contains(skuId)
                        || status == null || status.isBlank()
                        || asOf == null || qtyNode == null
                        || !qtyNode.isIntegralNumber()) {
                    return Map.of();
                }

                long availableQty = new BigDecimal(qtyNode.asText()).longValueExact();
                InventoryStock stock = new InventoryStock(
                        skuId, availableQty, status, Instant.parse(asOf));

                if (result.putIfAbsent(skuId, stock) != null) return Map.of();
            }

            return Map.copyOf(result);
        } catch (Exception ex) {
            return Map.of();
        }
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
            throw new IllegalArgumentException("Invalid Inventory base URL");
        }

        return uri;
    }
}

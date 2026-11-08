package com.taca.cart.integrations;

import com.sun.net.httpserver.HttpServer;
import com.taca.cart.application.ProductHydrationResult;
import com.taca.cart.config.ProductProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpProductHydrationClientTest {
    private static final String VALID_DETAIL = """
            {
              "data": {
                "product_id": "product-001",
                "status": "ACTIVE",
                "title": "Cotton jacket",
                "shop": {"shop_id": "shop-001"},
                "skus": [
                  {
                    "sku_id": "sku-001",
                    "status": "ACTIVE",
                    "attributes": {"color": "black", "size": "L"},
                    "price": {"sale_price": 249000, "currency": "VND"}
                  },
                  {
                    "sku_id": "sku-002",
                    "status": "ACTIVE",
                    "attributes": {"color": "white", "size": "M"},
                    "price": {"sale_price": 199000, "currency": "VND"}
                  },
                  {
                    "sku_id": "sku-003",
                    "status": "INACTIVE"
                  }
                ]
              },
              "meta": {"request_id": "req-product-001"}
            }
            """;

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile long responseDelayMs;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> acceptHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        responseStatus = 200;
        responseBody = VALID_DETAIL;
        responseDelayMs = 0;
        requestCount.set(0);
        requestedPath.set(null);
        acceptHeader.set(null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();

        server.setExecutor(executor);
        server.createContext("/api/v1/products/", exchange -> {
            requestCount.incrementAndGet();
            requestedPath.set(exchange.getRequestURI().getPath());
            acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));

            try {
                if (responseDelayMs > 0) Thread.sleep(responseDelayMs);

                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);

                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // Client may disconnect after timeout.
            } finally {
                exchange.close();
            }
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void shouldFetchActiveProductAndAllActiveSkus() {
        ProductHydrationResult result = client().fetchProduct("product-001");

        assertEquals(ProductHydrationResult.State.FOUND, result.state());
        assertEquals(2, result.skus().size());
        assertEquals(1, requestCount.get());
        assertEquals("/api/v1/products/product-001", requestedPath.get());
        assertEquals("application/json", acceptHeader.get());

        var black = result.skus().get("sku-001");
        assertNotNull(black);
        assertEquals("product-001", black.productId());
        assertEquals("sku-001", black.skuId());
        assertEquals("shop-001", black.shopId());
        assertEquals("Cotton jacket", black.title());
        assertEquals("color: black / size: L", black.skuLabel());
        assertEquals(249000L, black.unitPrice());

        var white = result.skus().get("sku-002");
        assertNotNull(white);
        assertEquals(199000L, white.unitPrice());
        assertFalse(result.skus().containsKey("sku-003"));
    }

    @Test
    void shouldReturnUnavailableWhenProductNotFound() {
        responseStatus = 404;

        assertState(ProductHydrationResult.State.UNAVAILABLE);
    }

    @Test
    void shouldReturnUnavailableWhenProductIsInactive() {
        responseBody = VALID_DETAIL.replace(
                "\"status\": \"ACTIVE\",",
                "\"status\": \"INACTIVE\",");

        assertState(ProductHydrationResult.State.UNAVAILABLE);
    }

    @Test
    void shouldReturnFoundWithEmptySkuList() {
        responseBody = """
                {
                  "data": {
                    "product_id": "product-001",
                    "status": "ACTIVE",
                    "title": "Cotton jacket",
                    "shop": {"shop_id": "shop-001"},
                    "skus": []
                  }
                }
                """;

        ProductHydrationResult result = client().fetchProduct("product-001");

        assertEquals(ProductHydrationResult.State.FOUND, result.state());
        assertTrue(result.skus().isEmpty());
    }

    @Test
    void shouldReturnStaleOnServerError() {
        responseStatus = 503;

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleOnUnauthorizedDependencyResponse() {
        responseStatus = 401;

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldNotFollowRedirects() {
        responseStatus = 302;

        assertState(ProductHydrationResult.State.STALE);
        assertEquals(1, requestCount.get());
    }

    @Test
    void shouldReturnStaleForMalformedJson() {
        responseBody = "{invalid-json";

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleWhenProductIdDoesNotMatch() {
        responseBody = VALID_DETAIL.replace(
                "\"product_id\": \"product-001\"",
                "\"product_id\": \"product-other\"");

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleWhenShopIdentityIsMissing() {
        responseBody = VALID_DETAIL.replace(
                "\"shop\": {\"shop_id\": \"shop-001\"},", "");

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleWhenSkuCollectionIsMissing() {
        responseBody = VALID_DETAIL.replace(
                "\"skus\": [", "\"other\": [");

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleForDecimalPrice() {
        responseBody = VALID_DETAIL.replace(
                "\"sale_price\": 249000",
                "\"sale_price\": 249000.5");

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleForOverflowingPrice() {
        responseBody = VALID_DETAIL.replace(
                "\"sale_price\": 249000",
                "\"sale_price\": 999999999999999999999999999");

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleForUnsupportedCurrency() {
        responseBody = VALID_DETAIL.replace(
                "\"currency\": \"VND\"",
                "\"currency\": \"USD\"");

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleForDuplicateSkuIds() {
        responseBody = VALID_DETAIL.replace(
                "\"sku_id\": \"sku-002\"",
                "\"sku_id\": \"sku-001\"");

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleForMalformedAttributes() {
        responseBody = VALID_DETAIL.replace(
                "\"attributes\": {\"color\": \"black\", \"size\": \"L\"}",
                "\"attributes\": [\"black\"]");

        assertState(ProductHydrationResult.State.STALE);
    }

    @Test
    void shouldReturnStaleWhenBaseUrlIsNotConfigured() {
        ProductProperties properties = properties(Duration.ofSeconds(2));
        ProductHydrationClient client = new HttpProductHydrationClient(properties, "");

        ProductHydrationResult result = client.fetchProduct("product-001");

        assertEquals(ProductHydrationResult.State.STALE, result.state());
        assertEquals(0, requestCount.get());
    }

    @Test
    void shouldRejectInvalidProductIdWithoutSendingRequest() {
        ProductHydrationResult result = client().fetchProduct("../admin");

        assertEquals(ProductHydrationResult.State.STALE, result.state());
        assertEquals(0, requestCount.get());
    }

    @Test
    void shouldReturnStaleOnHttpTimeout() {
        responseDelayMs = 2000;

        ProductHydrationResult result = client(Duration.ofMillis(500))
                .fetchProduct("product-001");

        assertEquals(ProductHydrationResult.State.STALE, result.state());
    }

    private void assertState(ProductHydrationResult.State expected) {
        ProductHydrationResult result = client().fetchProduct("product-001");

        assertEquals(expected, result.state());
        assertTrue(result.skus().isEmpty());
    }

    private HttpProductHydrationClient client() {
        return client(Duration.ofSeconds(2));
    }

    private HttpProductHydrationClient client(Duration timeout) {
        return new HttpProductHydrationClient(properties(timeout), baseUrl);
    }

    private ProductProperties properties(Duration timeout) {
        return new ProductProperties(timeout, Duration.ofSeconds(30), 10);
    }
}

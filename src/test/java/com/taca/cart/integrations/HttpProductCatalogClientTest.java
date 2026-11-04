package com.taca.cart.integrations;

import com.sun.net.httpserver.HttpServer;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.config.ProductProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class HttpProductCatalogClientTest {
    private static final String VALID_DETAIL = """
            {
              "data": {
                "product_id": "product-001",
                "status": "ACTIVE",
                "title": "Cotton jacket",
                "shop": {"shop_id": "shop-001"},
                "skus": [{
                  "sku_id": "sku-001",
                  "attributes": {"color": "black", "size": "L"},
                  "price": {"sale_price": 249000, "currency": "VND"}
                }]
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

    @BeforeEach
    void setUp() throws Exception {
        responseStatus = 200;
        responseBody = VALID_DETAIL;
        responseDelayMs = 0;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();

        server.setExecutor(executor);
        server.createContext("/api/v1/products/", exchange -> {
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
                // Client may close the connection after timeout.
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
    void shouldValidateActiveProductAndSku() {
        var result = client(Duration.ofSeconds(2))
                .validateSku("product-001", "sku-001");

        assertEquals("product-001", result.productId());
        assertEquals("sku-001", result.skuId());
        assertEquals("shop-001", result.shopId());
        assertEquals("Cotton jacket", result.title());
        assertEquals("color: black / size: L", result.skuLabel());
        assertEquals(249000L, result.unitPrice());
    }

    @Test
    void shouldRejectProductNotFound() {
        responseStatus = 404;
        assertError(CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectInactiveProduct() {
        responseBody = VALID_DETAIL.replace("\"status\": \"ACTIVE\"", "\"status\": \"INACTIVE\"");

        assertError(CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectSkuNotBelongingToProduct() {
        assertError(CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-other"));
    }

    @Test
    void shouldRejectInactiveSku() {
        responseBody = VALID_DETAIL.replace(
                "\"sku_id\": \"sku-001\"",
                "\"sku_id\": \"sku-001\", \"status\": \"INACTIVE\"");

        assertError(CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectDependencyServerError() {
        responseStatus = 503;

        assertError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectMalformedJson() {
        responseBody = "{invalid-json";

        assertError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectMissingShopIdentity() {
        responseBody = VALID_DETAIL.replace(
                "\"shop\": {\"shop_id\": \"shop-001\"},", "");

        assertError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectInvalidPrice() {
        responseBody = VALID_DETAIL.replace(
                "\"sale_price\": 249000", "\"sale_price\": 249000.5");

        assertError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectUnsupportedCurrency() {
        responseBody = VALID_DETAIL.replace("\"currency\": \"VND\"", "\"currency\": \"USD\"");

        assertError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectMismatchedProductIdentity() {
        responseBody = VALID_DETAIL.replace(
                "\"product_id\": \"product-001\"", "\"product_id\": \"product-other\"");

        assertError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> client(Duration.ofSeconds(2)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldHandleProductTimeout() {
        responseDelayMs = 500;

        assertError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> client(Duration.ofMillis(100)).validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldFailClosedWhenProductBaseUrlMissing() {
        var properties = new ProductProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(30), 10);

        var client = new HttpProductCatalogClient(properties, "");

        assertError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> client.validateSku("product-001", "sku-001"));
    }

    @Test
    void shouldRejectInvalidProductAndSkuIds() {
        var client = client(Duration.ofSeconds(2));

        assertError(CartErrorCode.CART_INVALID_INPUT,
                () -> client.validateSku("../admin", "sku-001"));
        assertError(CartErrorCode.CART_INVALID_INPUT,
                () -> client.validateSku("product-001", "sku/other"));
    }

    private HttpProductCatalogClient client(Duration timeout) {
        var properties = new ProductProperties(
                timeout, Duration.ofSeconds(30), 10);
        return new HttpProductCatalogClient(properties, baseUrl);
    }

    private void assertError(CartErrorCode code, Runnable action) {
        CartException ex = assertThrows(CartException.class, action::run);
        assertEquals(code, ex.getCode());
    }
}

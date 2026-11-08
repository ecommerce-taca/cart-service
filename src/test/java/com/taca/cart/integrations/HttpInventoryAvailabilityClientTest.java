package com.taca.cart.integrations;

import com.sun.net.httpserver.HttpServer;
import com.taca.cart.application.InventoryStock;
import com.taca.cart.config.InventoryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class HttpInventoryAvailabilityClientTest {
    private static final String TOKEN = "inventory-internal-test-token";
    private static final String VALID_RESPONSE = """
            {
              "data": [
                {
                  "sku_id": "sku-001",
                  "available_qty": 7,
                  "status": "ACTIVE",
                  "as_of": "2026-11-07T08:00:00Z"
                },
                {
                  "sku_id": "sku-002",
                  "available_qty": 0,
                  "status": "ACTIVE",
                  "as_of": "2026-11-07T08:00:00Z"
                }
              ],
              "meta": {"request_id": "req-inventory-001"}
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
    private final AtomicReference<String> requestedQuery = new AtomicReference<>();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        responseStatus = 200;
        responseBody = VALID_RESPONSE;
        responseDelayMs = 0;
        requestCount.set(0);
        requestedPath.set(null);
        requestedQuery.set(null);
        receivedToken.set(null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        server.createContext("/internal/inventory/availability", exchange -> {
            requestCount.incrementAndGet();
            requestedPath.set(exchange.getRequestURI().getPath());
            requestedQuery.set(exchange.getRequestURI().getRawQuery());
            receivedToken.set(exchange.getRequestHeaders()
                    .getFirst("X-Internal-Service-Token"));

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
    void shouldFetchInventoryInSingleBatchWithInternalToken() {
        Map<String, InventoryStock> result = client()
                .fetchAvailability(Set.of("sku-002", "sku-001"));

        assertEquals(2, result.size());
        assertEquals(7L, result.get("sku-001").availableQty());
        assertEquals(0L, result.get("sku-002").availableQty());
        assertEquals("ACTIVE", result.get("sku-001").status());

        assertEquals(1, requestCount.get());
        assertEquals("/internal/inventory/availability", requestedPath.get());
        assertEquals("sku_ids=sku-001,sku-002",
                URLDecoder.decode(requestedQuery.get(), StandardCharsets.UTF_8));
        assertEquals(TOKEN, receivedToken.get());
    }

    @Test
    void shouldAllowPartialInventoryResponse() {
        responseBody = VALID_RESPONSE.replaceFirst(
                "(?s),\\s*\\{\\s*\"sku_id\": \"sku-002\".*?\\}\\s*\\]",
                "]");

        Map<String, InventoryStock> result = client()
                .fetchAvailability(Set.of("sku-001", "sku-002"));

        assertEquals(1, result.size());
        assertTrue(result.containsKey("sku-001"));
        assertFalse(result.containsKey("sku-002"));
    }

    @Test
    void shouldReturnEmptyForEmptySkuSetWithoutNetworkCall() {
        assertTrue(client().fetchAvailability(Set.of()).isEmpty());
        assertEquals(0, requestCount.get());
    }

    @Test
    void shouldRejectMoreThanOneHundredSkus() {
        Set<String> ids = IntStream.range(0, 101)
                .mapToObj(i -> "sku-" + i)
                .collect(Collectors.toSet());

        assertTrue(client().fetchAvailability(ids).isEmpty());
        assertEquals(0, requestCount.get());
    }

    @Test
    void shouldRejectInvalidSkuIdBeforeSendingRequest() {
        assertTrue(client().fetchAvailability(Set.of("sku/other")).isEmpty());
        assertEquals(0, requestCount.get());
    }

    @Test
    void shouldReturnEmptyWhenInventoryIsNotConfigured() {
        var properties = properties(Duration.ofSeconds(1), 10);

        var missingUrl = new HttpInventoryAvailabilityClient(properties, "", TOKEN);
        var missingToken = new HttpInventoryAvailabilityClient(properties, baseUrl, "");

        assertTrue(missingUrl.fetchAvailability(Set.of("sku-001")).isEmpty());
        assertTrue(missingToken.fetchAvailability(Set.of("sku-001")).isEmpty());
        assertEquals(0, requestCount.get());
    }

    @Test
    void shouldReturnEmptyOnDependencyServerError() {
        responseStatus = 503;

        assertTrue(client().fetchAvailability(Set.of("sku-001")).isEmpty());
    }

    @Test
    void shouldReturnEmptyOnUnauthorizedResponse() {
        responseStatus = 401;

        assertTrue(client().fetchAvailability(Set.of("sku-001")).isEmpty());
    }

    @Test
    void shouldNotFollowRedirects() {
        responseStatus = 302;

        assertTrue(client().fetchAvailability(Set.of("sku-001")).isEmpty());
        assertEquals(1, requestCount.get());
    }

    @Test
    void shouldReturnEmptyForMalformedJson() {
        responseBody = "{invalid-json";

        assertTrue(client().fetchAvailability(Set.of("sku-001")).isEmpty());
    }

    @Test
    void shouldRejectNegativeQuantity() {
        responseBody = VALID_RESPONSE.replace(
                "\"available_qty\": 7", "\"available_qty\": -1");

        assertTrue(client().fetchAvailability(
                Set.of("sku-001", "sku-002")).isEmpty());
    }

    @Test
    void shouldRejectDecimalQuantity() {
        responseBody = VALID_RESPONSE.replace(
                "\"available_qty\": 7", "\"available_qty\": 7.5");

        assertTrue(client().fetchAvailability(
                Set.of("sku-001", "sku-002")).isEmpty());
    }

    @Test
    void shouldRejectOverflowingQuantity() {
        responseBody = VALID_RESPONSE.replace(
                "\"available_qty\": 7",
                "\"available_qty\": 999999999999999999999999");

        assertTrue(client().fetchAvailability(
                Set.of("sku-001", "sku-002")).isEmpty());
    }

    @Test
    void shouldRejectUnexpectedSkuInResponse() {
        responseBody = VALID_RESPONSE.replace(
                "\"sku_id\": \"sku-002\"", "\"sku_id\": \"sku-other\"");

        assertTrue(client().fetchAvailability(
                Set.of("sku-001", "sku-002")).isEmpty());
    }

    @Test
    void shouldRejectDuplicateSkuInResponse() {
        responseBody = VALID_RESPONSE.replace(
                "\"sku_id\": \"sku-002\"", "\"sku_id\": \"sku-001\"");

        assertTrue(client().fetchAvailability(
                Set.of("sku-001", "sku-002")).isEmpty());
    }

    @Test
    void shouldRejectInvalidTimestamp() {
        responseBody = VALID_RESPONSE.replace(
                "2026-11-07T08:00:00Z", "invalid-time");

        assertTrue(client().fetchAvailability(
                Set.of("sku-001", "sku-002")).isEmpty());
    }

    @Test
    void shouldFallbackWhenInventoryRequestTimesOut() {
        responseDelayMs = 2000;

        var result = client(Duration.ofMillis(500), 10)
                .fetchAvailability(Set.of("sku-001"));

        assertTrue(result.isEmpty());
    }
    
    @Test
    void shouldLimitConcurrentInventoryRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger serverCalls = new AtomicInteger();

        server.removeContext("/internal/inventory/availability");
        server.createContext("/internal/inventory/availability", exchange -> {
            serverCalls.incrementAndGet();
            entered.countDown();

            try {
                if (!release.await(5, TimeUnit.SECONDS)) return;

                byte[] bytes = """
                    {
                      "data": [
                        {
                          "sku_id": "sku-001",
                          "available_qty": 7,
                          "status": "ACTIVE",
                          "as_of": "2026-11-07T08:00:00Z"
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);

                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
            } finally {
                exchange.close();
            }
        });

        var client = client(Duration.ofSeconds(5), 1);
        ExecutorService workers = Executors.newSingleThreadExecutor();

        try {
            Future<Map<String, InventoryStock>> first =
                    workers.submit(() -> client.fetchAvailability(Set.of("sku-001")));

            assertTrue(entered.await(3, TimeUnit.SECONDS));

            Map<String, InventoryStock> overloaded =
                    client.fetchAvailability(Set.of("sku-002"));

            assertTrue(overloaded.isEmpty());
            assertEquals(1, serverCalls.get());

            release.countDown();

            Map<String, InventoryStock> firstResult =
                    first.get(10, TimeUnit.SECONDS);

            assertEquals(1, firstResult.size());
            assertEquals(7L, firstResult.get("sku-001").availableQty());
            assertEquals(1, serverCalls.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private HttpInventoryAvailabilityClient client() {
        return client(Duration.ofSeconds(1), 10);
    }

    private HttpInventoryAvailabilityClient client(Duration timeout,
                                                   int maxConcurrency) {
        return new HttpInventoryAvailabilityClient(
                properties(timeout, maxConcurrency), baseUrl, TOKEN);
    }

    private InventoryProperties properties(Duration timeout,
                                           int maxConcurrency) {
        return new InventoryProperties(timeout, 100, maxConcurrency);
    }
}

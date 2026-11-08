package com.taca.cart.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.application.*;
import com.taca.cart.config.CartProperties;
import com.taca.cart.config.InventoryProperties;
import com.taca.cart.config.ProductProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CartDependencyInstrumentationTest {
    private static final Instant NOW =
            Instant.parse("2026-11-08T00:00:00Z");

    private static final String PRODUCT_JSON = """
            {
              "data": {
                "product_id": "product-001",
                "status": "ACTIVE",
                "title": "Cotton jacket",
                "shop": {"shop_id": "shop-001"},
                "skus": [{
                  "sku_id": "sku-001",
                  "status": "ACTIVE",
                  "attributes": {"color": "black"},
                  "price": {"sale_price": 249000, "currency": "VND"}
                }]
              }
            }
            """;

    private static final String INVENTORY_JSON = """
            {
              "data": [{
                "sku_id": "sku-001",
                "available_qty": 7,
                "status": "ACTIVE",
                "as_of": "2026-11-08T00:00:00Z"
              }]
            }
            """;

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private SimpleMeterRegistry registry;
    private CartDependencyMetrics metrics;
    private final AtomicInteger productRequests = new AtomicInteger();
    private final AtomicInteger inventoryRequests = new AtomicInteger();

    private volatile int productStatus;
    private volatile int inventoryStatus;
    private volatile long productDelayMs;
    private volatile long inventoryDelayMs;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SimpleMeterRegistry();
        metrics = new CartDependencyMetrics(registry);

        productStatus = 200;
        inventoryStatus = 200;
        productDelayMs = 0;
        inventoryDelayMs = 0;
        productRequests.set(0);
        inventoryRequests.set(0);

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        server.createContext("/api/v1/products/", exchange -> {
            productRequests.incrementAndGet();
            respond(exchange, productStatus, PRODUCT_JSON, productDelayMs);
        });

        server.createContext("/internal/inventory/availability", exchange -> {
            inventoryRequests.incrementAndGet();
            respond(exchange, inventoryStatus, INVENTORY_JSON, inventoryDelayMs);
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        if (registry != null) registry.close();
    }

    @Test
    void shouldCountOnlyRealProductHttpCallWhenCacheHits() {
        ProductProperties properties = productProperties(
                Duration.ofSeconds(2));

        ProductHydrationClient httpClient = new HttpProductHydrationClient(
                properties, baseUrl, metrics);

        CachedProductHydrationService cache =
                new CachedProductHydrationService(
                        httpClient, properties, Clock.systemUTC(), metrics);

        assertEquals(ProductHydrationResult.State.FOUND,
                cache.getProduct("product-001").state());
        assertEquals(ProductHydrationResult.State.FOUND,
                cache.getProduct("product-001").state());

        assertEquals(1, productRequests.get());
        assertEquals(1.0, productHttp("found"));
        assertEquals(1.0, cacheLookup("miss"));
        assertEquals(1.0, cacheLookup("hit"));
        assertEquals(0.0, staleFallbacks());
    }

    @Test
    void shouldNotCacheStaleProductResponses() {
        productStatus = 503;

        ProductProperties properties = productProperties(
                Duration.ofSeconds(2));

        ProductHydrationClient client = new HttpProductHydrationClient(
                properties, baseUrl, metrics);

        CachedProductHydrationService cache =
                new CachedProductHydrationService(
                        client, properties, Clock.systemUTC(), metrics);

        assertEquals(ProductHydrationResult.State.STALE,
                cache.getProduct("product-001").state());
        assertEquals(ProductHydrationResult.State.STALE,
                cache.getProduct("product-001").state());

        assertEquals(2, productRequests.get());
        assertEquals(2.0, productHttp("http_error"));
        assertEquals(2.0, cacheLookup("miss"));
        assertEquals(0.0, cacheLookup("hit"));
        assertEquals(2.0, staleFallbacks());
    }

    @Test
    void shouldRecordProductHttpTimeout() {
        productDelayMs = 1000;

        ProductProperties properties = productProperties(
                Duration.ofMillis(150));

        HttpProductHydrationClient client =
                new HttpProductHydrationClient(
                        properties, baseUrl, metrics);

        ProductHydrationResult result =
                client.fetchProduct("product-001");

        assertEquals(ProductHydrationResult.State.STALE, result.state());
        assertEquals(1.0, productHttp("timeout"));
        assertEquals(1L, registry.get("cart.product.http.duration")
                .tag("outcome", "timeout").timer().count());
    }

    @Test
    void shouldRecordInventoryHttpCompleteAndInvalidSkip() {
        HttpInventoryAvailabilityClient client = inventoryClient(
                Duration.ofSeconds(2));

        Map<String, InventoryStock> result =
                client.fetchAvailability(Set.of("sku-001"));

        assertEquals(1, result.size());
        assertEquals(7L, result.get("sku-001").availableQty());
        assertEquals(1.0, inventoryHttp("complete"));

        assertTrue(client.fetchAvailability(Set.of("invalid/sku")).isEmpty());
        assertEquals(1.0, inventorySkip("invalid"));
        assertEquals(1, inventoryRequests.get());
    }

    @Test
    void shouldRecordPartialInventoryResponse() {
        HttpInventoryAvailabilityClient client = inventoryClient(
                Duration.ofSeconds(2));

        Map<String, InventoryStock> result =
                client.fetchAvailability(Set.of("sku-001", "sku-002"));

        assertEquals(1, result.size());
        assertEquals(1.0, inventoryHttp("partial"));
        assertEquals(1, inventoryRequests.get());
    }

    @Test
    void shouldRecordInventoryTimeout() {
        inventoryDelayMs = 1000;

        HttpInventoryAvailabilityClient client =
                inventoryClient(Duration.ofMillis(150));

        Map<String, InventoryStock> result =
                client.fetchAvailability(Set.of("sku-001"));

        assertTrue(result.isEmpty());
        assertEquals(1.0, inventoryHttp("timeout"));
        assertEquals(1L, registry.get("cart.inventory.http.duration")
                .tag("outcome", "timeout").timer().count());
    }

    @Test
    void shouldCountUnknownFromFinalBuyerHydration() {
        ProductHydrationClient productClient = productId ->
                ProductHydrationResult.found(Map.of(
                        "sku-001", new ValidatedSkuSnapshot(
                                "product-001", "sku-001", "shop-001",
                                "Current title", "Black", 249000L)));

        InventoryAvailabilityClient inventoryClient =
                skuIds -> Map.of();

        ProductProperties productProperties =
                productProperties(Duration.ofSeconds(2));

        CachedProductHydrationService cachedProducts =
                new CachedProductHydrationService(
                        productClient, productProperties,
                        Clock.systemUTC(), metrics);

        CartProperties cartProperties = new CartProperties(
                100, 1, 999,
                Duration.ofSeconds(3),
                Duration.ofHours(24), 3);

        InventoryProperties inventoryProperties =
                new InventoryProperties(
                        Duration.ofSeconds(1), 100, 10);

        CartHydrationOrchestrator orchestrator =
                new CartHydrationOrchestrator(
                        cachedProducts,
                        new CartProductResponseHydrator(),
                        inventoryClient,
                        new CartInventoryResponseHydrator(),
                        cartProperties,
                        productProperties,
                        inventoryProperties,
                        metrics);

        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 1L, "Old title", "Old label",
                249000L, true, NOW, NOW);

        Cart cart = new Cart(
                "cart-001", "buyer-001",
                List.of(item), 1L, NOW, NOW);

        var response = orchestrator.hydrate(Optional.of(cart));

        assertEquals(AvailabilityStatus.UNKNOWN,
                response.items().getFirst().availabilityStatus());

        assertEquals(1.0, registry.get("cart.hydration.items")
                .tag("availability_status", "unknown")
                .counter().count());

        assertEquals(1.0, cacheLookup("miss"));
    }

    private ProductProperties productProperties(Duration timeout) {
        return new ProductProperties(
                timeout, Duration.ofSeconds(30), 10);
    }

    private HttpInventoryAvailabilityClient inventoryClient(
            Duration timeout) {
        return new HttpInventoryAvailabilityClient(
                new InventoryProperties(timeout, 100, 10),
                baseUrl, "internal-test-token", metrics);
    }

    private double productHttp(String outcome) {
        return registry.get("cart.product.http.requests")
                .tag("outcome", outcome).counter().count();
    }

    private double inventoryHttp(String outcome) {
        return registry.get("cart.inventory.http.requests")
                .tag("outcome", outcome).counter().count();
    }

    private double cacheLookup(String result) {
        return registry.get("cart.product.cache.lookups")
                .tag("result", result).counter().count();
    }

    private double inventorySkip(String reason) {
        return registry.get("cart.inventory.skipped")
                .tag("reason", reason).counter().count();
    }

    private double staleFallbacks() {
        return registry.get("cart.product.stale.fallbacks")
                .counter().count();
    }

    private void respond(
            HttpExchange exchange, int status, String body, long delayMs) {
        try {
            if (delayMs > 0) Thread.sleep(delayMs);

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);

            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Client may disconnect after its timeout.
        } finally {
            exchange.close();
        }
    }
}

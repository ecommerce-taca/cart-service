package com.taca.cart.contracts;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.*;
import com.taca.cart.config.InventoryProperties;
import com.taca.cart.config.ProductProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.HttpInventoryAvailabilityClient;
import com.taca.cart.integrations.HttpProductCatalogClient;
import com.taca.cart.integrations.HttpProductHydrationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CartDependencyHttpContractTest {
    private static final String PRODUCT = "product-001";
    private static final String SKU = "sku-001";
    private static final String SKU_TWO = "sku-002";
    private static final String TOKEN = "inventory-contract-token";
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;

    private volatile String productBody;
    private volatile String inventoryBody;
    private volatile int productStatus;
    private volatile int inventoryStatus;

    private final AtomicInteger productRequests = new AtomicInteger();
    private final AtomicInteger inventoryRequests = new AtomicInteger();
    private final AtomicReference<String> productPath = new AtomicReference<>();
    private final AtomicReference<String> productAccept = new AtomicReference<>();
    private final AtomicReference<String> inventoryPath = new AtomicReference<>();
    private final AtomicReference<String> inventoryQuery = new AtomicReference<>();
    private final AtomicReference<String> inventoryToken = new AtomicReference<>();
    private final AtomicReference<String> inventoryAccept = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        productBody = fixture("product-detail-cart-v1.json");
        inventoryBody = fixture("inventory-availability-cart-v1.json");
        productStatus = 200;
        inventoryStatus = 200;

        productRequests.set(0);
        inventoryRequests.set(0);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        server.createContext("/api/v1/products/", exchange -> {
            productRequests.incrementAndGet();
            productPath.set(exchange.getRequestURI().getPath());
            productAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            respond(exchange, productStatus, productBody);
        });

        server.createContext("/internal/inventory/availability", exchange -> {
            inventoryRequests.incrementAndGet();
            inventoryPath.set(exchange.getRequestURI().getPath());
            inventoryQuery.set(exchange.getRequestURI().getRawQuery());
            inventoryToken.set(exchange.getRequestHeaders()
                    .getFirst("X-Internal-Service-Token"));
            inventoryAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            respond(exchange, inventoryStatus, inventoryBody);
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
    void shouldInterpretProductContractConsistentlyForAddAndGet() {
        ValidatedSkuSnapshot validated =
                catalogClient().validateSku(PRODUCT, SKU);
        ProductHydrationResult hydrated =
                hydrationClient().fetchProduct(PRODUCT);

        assertEquals(ProductHydrationResult.State.FOUND, hydrated.state());
        assertEquals(validated, hydrated.skus().get(SKU));
        assertEquals("shop-001", validated.shopId());
        assertEquals("Coffee Beans", validated.title());
        assertEquals("roast: medium / size: 250g", validated.skuLabel());
        assertEquals(249000L, validated.unitPrice());

        assertEquals(2, productRequests.get());
        assertEquals("/api/v1/products/product-001", productPath.get());
        assertEquals("application/json", productAccept.get());
        assertEquals(0, inventoryRequests.get());
    }

    @Test
    void shouldHydrateAvailableCartFromBothDependencies() {
        Cart cart = storedCart();
        CartResponse response = hydrateCart(cart);

        var item = response.items().getFirst();
        assertEquals(AvailabilityStatus.AVAILABLE, item.availabilityStatus());
        assertTrue(item.available());
        assertEquals("Coffee Beans", item.titleSnapshot());
        assertEquals("roast: medium / size: 250g", item.skuLabelSnapshot());
        assertEquals(249000L, item.unitPriceSnapshot());
        assertEquals(498000L, item.lineTotal());

        assertEquals(120000L, cart.items().getFirst().unitPriceSnapshot());
        assertEquals(1L, cart.version());
        assertEquals(1, productRequests.get());
        assertEquals(1, inventoryRequests.get());
    }

    @Test
    void shouldMarkItemOutOfStockWhenInventoryQuantityIsInsufficient() {
        inventoryBody = inventoryBody.replace(
                "\"available_qty\": 7", "\"available_qty\": 1");

        CartResponse response = hydrateCart(storedCart());
        var item = response.items().getFirst();

        assertEquals(AvailabilityStatus.OUT_OF_STOCK,
                item.availabilityStatus());
        assertFalse(item.available());
        assertEquals(249000L, item.unitPriceSnapshot());
        assertEquals(498000L, item.lineTotal());
    }

    @Test
    void shouldMapProduct404ToAddRejectionAndGetUnavailable() {
        productStatus = 404;

        assertProductError(CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                () -> catalogClient().validateSku(PRODUCT, SKU));

        ProductHydrationResult result = hydrationClient().fetchProduct(PRODUCT);
        assertEquals(ProductHydrationResult.State.UNAVAILABLE, result.state());

        CartResponse response = productHydrate(storedCart(), result);
        assertEquals(AvailabilityStatus.PRODUCT_UNAVAILABLE,
                response.items().getFirst().availabilityStatus());
        assertFalse(response.items().getFirst().available());

        assertEquals(0, inventoryRequests.get());
    }

    @Test
    void shouldMapProduct503ToAddFailureAndGetStale() {
        productStatus = 503;

        assertProductError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> catalogClient().validateSku(PRODUCT, SKU));

        ProductHydrationResult result = hydrationClient().fetchProduct(PRODUCT);
        assertEquals(ProductHydrationResult.State.STALE, result.state());

        CartResponse response = productHydrate(storedCart(), result);
        var item = response.items().getFirst();

        assertEquals(AvailabilityStatus.STALE, item.availabilityStatus());
        assertEquals(120000L, item.unitPriceSnapshot());
        assertEquals(240000L, item.lineTotal());
        assertEquals(0, inventoryRequests.get());
    }

    @Test
    void shouldFailSafelyWhenProductContractLosesShopIdentity() {
        productBody = productBody.replace(
                "\"shop\": {\"shop_id\": \"shop-001\"},", "");

        assertProductError(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                () -> catalogClient().validateSku(PRODUCT, SKU));

        ProductHydrationResult result = hydrationClient().fetchProduct(PRODUCT);
        assertEquals(ProductHydrationResult.State.STALE, result.state());

        CartResponse response = productHydrate(storedCart(), result);
        assertEquals(AvailabilityStatus.STALE,
                response.items().getFirst().availabilityStatus());
    }

    @Test
    void shouldRejectInactiveSkuWithoutDisablingOtherSku() {
        String original = productBody;
        productBody = original.replaceFirst(
                "(\"sku_id\"\\s*:\\s*\"sku-001\"\\s*,\\s*\"status\"\\s*:\\s*)\"ACTIVE\"",
                "$1\"INACTIVE\"");
        assertNotEquals(original, productBody, "SKU-001 status was not updated");

        assertProductError(CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                () -> catalogClient().validateSku(PRODUCT, SKU));

        ProductHydrationResult result = hydrationClient().fetchProduct(PRODUCT);

        assertEquals(ProductHydrationResult.State.FOUND, result.state());
        assertFalse(result.skus().containsKey(SKU));
        assertTrue(result.skus().containsKey(SKU_TWO));

        CartResponse response = productHydrate(storedCart(), result);
        assertEquals(AvailabilityStatus.PRODUCT_UNAVAILABLE,
                response.items().getFirst().availabilityStatus());
    }

    @Test
    void shouldSendInventoryBatchWithInternalToken() {
        Map<String, InventoryStock> result = inventoryClient(TOKEN)
                .fetchAvailability(Set.of(SKU_TWO, SKU));

        assertEquals(1, inventoryRequests.get());
        assertEquals("/internal/inventory/availability", inventoryPath.get());
        assertEquals("sku_ids=sku-001,sku-002",
                URLDecoder.decode(inventoryQuery.get(), StandardCharsets.UTF_8));
        assertEquals(TOKEN, inventoryToken.get());
        assertEquals("application/json", inventoryAccept.get());

        assertEquals(1, result.size());
        assertEquals(7L, result.get(SKU).availableQty());
        assertEquals("ACTIVE", result.get(SKU).status());
        assertEquals(NOW, result.get(SKU).asOf());
    }

    @Test
    void shouldAcceptPartialInventoryResponseWithoutInventingStock() {
        Map<String, InventoryStock> result = inventoryClient(TOKEN)
                .fetchAvailability(Set.of(SKU, SKU_TWO));

        assertTrue(result.containsKey(SKU));
        assertFalse(result.containsKey(SKU_TWO));
        assertEquals(1, result.size());
        assertEquals(1, inventoryRequests.get());
    }

    @Test
    void shouldRejectInvalidInventoryQuantityAndRemainUnknown() {
        inventoryBody = inventoryBody.replace(
                "\"available_qty\": 7", "\"available_qty\": -1");

        assertTrue(inventoryClient(TOKEN)
                .fetchAvailability(Set.of(SKU)).isEmpty());

        CartResponse response = hydrateCart(storedCart());
        var item = response.items().getFirst();

        assertEquals(AvailabilityStatus.UNKNOWN, item.availabilityStatus());
        assertEquals(249000L, item.unitPriceSnapshot());
        assertEquals(498000L, item.lineTotal());
    }

    @Test
    void shouldFallbackWhenInventoryRejectsOrFailsRequest() {
        inventoryStatus = 401;
        assertTrue(inventoryClient(TOKEN)
                .fetchAvailability(Set.of(SKU)).isEmpty());

        inventoryStatus = 503;
        assertTrue(inventoryClient(TOKEN)
                .fetchAvailability(Set.of(SKU)).isEmpty());

        assertEquals(2, inventoryRequests.get());
    }

    @Test
    void shouldNotCallInventoryWithoutInternalToken() {
        Map<String, InventoryStock> result =
                inventoryClient("").fetchAvailability(Set.of(SKU));

        assertTrue(result.isEmpty());
        assertEquals(0, inventoryRequests.get());
    }

    private HttpProductCatalogClient catalogClient() {
        return new HttpProductCatalogClient(productProperties(), baseUrl);
    }

    private HttpProductHydrationClient hydrationClient() {
        return new HttpProductHydrationClient(productProperties(), baseUrl);
    }

    private HttpInventoryAvailabilityClient inventoryClient(String token) {
        return new HttpInventoryAvailabilityClient(
                new InventoryProperties(Duration.ofSeconds(2), 100, 10),
                baseUrl, token);
    }

    private ProductProperties productProperties() {
        return new ProductProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(30), 10);
    }

    private Cart storedCart() {
        CartItem item = new CartItem(
                "item-001", PRODUCT, SKU, "shop-001",
                2, 1L, "Stored coffee", "Stored 250g",
                120000L, true, NOW, NOW);

        return new Cart("cart-001", "buyer-001",
                List.of(item), 1L, NOW, NOW);
    }

    private CartResponse hydrateCart(Cart cart) {
        ProductHydrationResult product =
                hydrationClient().fetchProduct(PRODUCT);
        Map<String, ProductHydrationResult> products =
                Map.of(PRODUCT, product);

        CartResponse productResponse = new CartProductResponseHydrator()
                .hydrate(Optional.of(cart), products);

        Map<String, InventoryStock> stocks = inventoryClient(TOKEN)
                .fetchAvailability(Set.of(SKU));

        return new CartInventoryResponseHydrator()
                .hydrate(productResponse, products, stocks);
    }

    private CartResponse productHydrate(
            Cart cart, ProductHydrationResult product) {
        return new CartProductResponseHydrator().hydrate(
                Optional.of(cart), Map.of(PRODUCT, product));
    }

    private void assertProductError(CartErrorCode expected, Runnable action) {
        CartException error = assertThrows(CartException.class, action::run);
        assertEquals(expected, error.getCode());
    }

    private String fixture(String filename) throws Exception {
        String path = "contracts/dependencies/" + filename;

        try (InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(path),
                "Missing fixture: " + path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);

        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
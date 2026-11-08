package com.taca.cart.api;

import com.taca.cart.application.InventoryStock;
import com.taca.cart.application.ProductHydrationResult;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.integrations.ProductHydrationClient;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "cart.indexes.initialize=false",
                "cart.hydrate-timeout=450ms",
                "product.timeout=250ms",
                "inventory.timeout=150ms"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartDependencyDegradationHttpIntegrationTest {
    private static final String PATH = "/api/v1/cart";
    private static final String BUYER = "buyer-001";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("product.base-url", () -> "");
        registry.add("inventory.base-url", () -> "");
        registry.add("management.tracing.sampling.probability", () -> 1.0);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CartPersistenceMapper mapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private ProductHydrationClient productClient;

    @MockitoBean
    private InventoryAvailabilityClient inventoryClient;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys")
                .deleteMany(new Document());
    }

    @Test
    void shouldReturnStaleWhenProductClientThrows() throws Exception {
        seed(List.of(item("pfail", "product-pfail", "sku-pfail")));
        Document before = rawCart();

        when(productClient.fetchProduct("product-pfail"))
                .thenThrow(new IllegalStateException("Product unavailable"));

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("STALE"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot")
                        .value(249000));

        assertEquals(before, rawCart());
        verifyNoInteractions(inventoryClient);
        assertTrue(metric("cart.product.stale.fallbacks") >= 1);
    }

    @Test
    void shouldReturnUnknownWhenInventoryThrows() throws Exception {
        seed(List.of(item("ifail", "product-ifail", "sku-ifail")));
        Document before = rawCart();

        when(productClient.fetchProduct("product-ifail"))
                .thenReturn(found("product-ifail", "sku-ifail"));

        when(inventoryClient.fetchAvailability(Set.of("sku-ifail")))
                .thenThrow(new IllegalStateException("Inventory unavailable"));

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));

        assertEquals(before, rawCart());
        assertTrue(meterRegistry.get("cart.hydration.items")
                .tag("availability_status", "unknown")
                .counter().count() >= 1);
    }

    @Test
    void shouldReturnMixedFallbackWhenDependenciesFailTogether()
            throws Exception {
        seed(List.of(
                item("good", "product-good", "sku-good"),
                item("bad", "product-bad", "sku-bad")));

        Document before = rawCart();

        when(productClient.fetchProduct("product-good"))
                .thenReturn(found("product-good", "sku-good"));

        when(productClient.fetchProduct("product-bad"))
                .thenThrow(new IllegalStateException("Product failure"));

        when(inventoryClient.fetchAvailability(Set.of("sku-good")))
                .thenThrow(new IllegalStateException("Inventory failure"));

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_count").value(2))
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[1].availability_status")
                        .value("STALE"));

        assertEquals(before, rawCart());
        verify(inventoryClient).fetchAvailability(Set.of("sku-good"));
    }

    @Test
    void shouldFinishWithinBudgetWhenProductIsSlow()
            throws Exception {
        seed(List.of(item("slowp", "product-slowp", "sku-slowp")));
        Document before = rawCart();

        when(productClient.fetchProduct("product-slowp"))
                .thenAnswer(invocation -> {
                    sleepUntilInterruptedOrComplete();
                    return ProductHydrationResult.stale();
                });

        long started = System.nanoTime();

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("STALE"));

        long elapsed = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertTrue(elapsed < 2500,
                "Product hydration took too long: " + elapsed + "ms");
        assertEquals(before, rawCart());
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void shouldFinishWithinBudgetWhenInventoryIsSlow()
            throws Exception {
        seed(List.of(item("slowi", "product-slowi", "sku-slowi")));
        Document before = rawCart();

        when(productClient.fetchProduct("product-slowi"))
                .thenReturn(found("product-slowi", "sku-slowi"));

        when(inventoryClient.fetchAvailability(Set.of("sku-slowi")))
                .thenAnswer(invocation -> {
                    sleepUntilInterruptedOrComplete();
                    return Map.<String, InventoryStock>of();
                });

        long started = System.nanoTime();

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("UNKNOWN"));

        long elapsed = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertTrue(elapsed < 2500,
                "Inventory hydration took too long: " + elapsed + "ms");
        assertEquals(before, rawCart());
        verify(inventoryClient).fetchAvailability(Set.of("sku-slowi"));
    }

    @Test
    void shouldRecoverAfterTransientProductFailure()
            throws Exception {
        seed(List.of(item("recover", "product-recover", "sku-recover")));
        Document before = rawCart();

        when(productClient.fetchProduct("product-recover"))
                .thenReturn(
                        ProductHydrationResult.stale(),
                        found("product-recover", "sku-recover"));

        when(inventoryClient.fetchAvailability(Set.of("sku-recover")))
                .thenReturn(Map.of(
                        "sku-recover",
                        new InventoryStock("sku-recover", 7L, "ACTIVE", NOW)));

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("STALE"));

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("AVAILABLE"));

        verify(productClient, times(2))
                .fetchProduct("product-recover");
        verify(inventoryClient)
                .fetchAvailability(Set.of("sku-recover"));

        assertEquals(before, rawCart());
    }

    @Test
    void shouldNotCallInventoryForUnavailableProduct()
            throws Exception {
        seed(List.of(item("inactive", "product-inactive", "sku-inactive")));
        Document before = rawCart();

        when(productClient.fetchProduct("product-inactive"))
                .thenReturn(ProductHydrationResult.unavailable());

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("PRODUCT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].available").value(false));

        verifyNoInteractions(inventoryClient);
        assertEquals(before, rawCart());
    }

    @Test
    void shouldPreserveRequestIdAndTraceRequestDuringFallback()
            throws Exception {
        seed(List.of(item("trace", "product-trace", "sku-trace")));

        when(productClient.fetchProduct("product-trace"))
                .thenReturn(ProductHydrationResult.stale());

        mvc.perform(get(PATH)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .header("X-Request-ID", "req-phase8e-fallback")
                        .header("traceparent",
                                "00-11223344556677889900aabbccddeeff-1234567890abcdef-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Request-ID", "req-phase8e-fallback"))
                .andExpect(jsonPath("$.meta.request_id")
                        .value("req-phase8e-fallback"))
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("STALE"));
    }

    @Test
    void shouldSkipDependenciesForExistingEmptyCart()
            throws Exception {
        seed(List.of());
        Document before = rawCart();

        mvc.perform(buyerGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_count").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        assertEquals(before, rawCart());
        verifyNoInteractions(productClient, inventoryClient);
    }

    private org.springframework.test.web.servlet.request
            .MockHttpServletRequestBuilder buyerGet() {
        return get(PATH)
                .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                .header("X-User-ID", BUYER);
    }

    private void seed(List<CartItem> items) {
        Cart cart = new Cart("cart-buyer-001", BUYER, items, 3L, NOW, NOW);
        mongoTemplate.insert(mapper.toDocument(cart));
    }

    private CartItem item(String suffix, String productId, String skuId) {
        return new CartItem(
                "item-" + suffix, productId, skuId, "shop-001",
                2, 3L, "Stored title", "Default",
                249000L, true, NOW, NOW);
    }

    private ProductHydrationResult found(String productId, String skuId) {
        return ProductHydrationResult.found(Map.of(
                skuId, new ValidatedSkuSnapshot(
                        productId, skuId, "shop-001",
                        "Current title", "Default", 229000L)));
    }

    private Document rawCart() {
        Document result = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", BUYER))
                .first();
        assertNotNull(result);
        return result;
    }

    private double metric(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private void sleepUntilInterruptedOrComplete() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

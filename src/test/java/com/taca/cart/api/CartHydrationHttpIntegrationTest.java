package com.taca.cart.api;

import com.taca.cart.application.CachedProductHydrationService;
import com.taca.cart.application.InventoryStock;
import com.taca.cart.application.ProductHydrationResult;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "cart.indexes.initialize=false"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartHydrationHttpIntegrationTest {
    private static final String PATH = "/api/v1/cart";
    private static final String BUYER = "buyer-001";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("product.base-url", () -> "");
        registry.add("inventory.base-url", () -> "");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CartPersistenceMapper mapper;

    @MockitoBean
    private CachedProductHydrationService productService;

    @MockitoBean
    private InventoryAvailabilityClient inventoryClient;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys").deleteMany(new Document());
    }

    @Test
    void shouldReturnEmptyCartWithoutCallingDependenciesOrCreatingDocument()
            throws Exception {
        mvc.perform(get(PATH).with(gateway(BUYER))
                        .header("X-Request-ID", "req-empty"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-empty"))
                .andExpect(jsonPath("$.data.cart_id").value(nullValue()))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.item_count").value(0))
                .andExpect(jsonPath("$.data.updated_at").value(nullValue()))
                .andExpect(jsonPath("$.meta.request_id").value("req-empty"));

        assertEquals(0L, countCarts());
        verifyNoInteractions(productService, inventoryClient);
    }

    @Test
    void shouldReturnPersistedEmptyCartWithoutHydration() throws Exception {
        seed(cart(BUYER, List.of()));
        Document before = rawCart(BUYER);

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value("cart-buyer-001"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.item_count").value(0))
                .andExpect(jsonPath("$.data.updated_at")
                        .value("2026-11-04T00:00:00Z"));

        assertEquals(before, rawCart(BUYER));
        verifyNoInteractions(productService, inventoryClient);
    }

    @Test
    void shouldReturnHydratedPriceAndAvailableStockWithoutWritingMongo()
            throws Exception {
        seed(cart(BUYER, List.of(item("product-001", "sku-001"))));
        Document before = rawCart(BUYER);

        when(productService.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));
        when(inventoryClient.fetchAvailability(Set.of("sku-001")))
                .thenReturn(Map.of("sku-001", stock("sku-001", 7)));

        mvc.perform(get(PATH).with(gateway(BUYER))
                        .header("X-Request-ID", "req-hydrated"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-hydrated"))
                .andExpect(jsonPath("$.meta.request_id").value("req-hydrated"))
                .andExpect(jsonPath("$.data.cart_id").value("cart-buyer-001"))
                .andExpect(jsonPath("$.data.item_count").value(1))
                .andExpect(jsonPath("$.data.items[0].item_id").value("item-sku-001"))
                .andExpect(jsonPath("$.data.items[0].product_id").value("product-001"))
                .andExpect(jsonPath("$.data.items[0].sku_id").value("sku-001"))
                .andExpect(jsonPath("$.data.items[0].title_snapshot").value("Current title"))
                .andExpect(jsonPath("$.data.items[0].sku_label_snapshot").value("Black / L"))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot").value(229000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].line_total").value(458000))
                .andExpect(jsonPath("$.data.items[0].available").value(true))
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("AVAILABLE"))
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].item_revision").doesNotExist());

        assertEquals(before, rawCart(BUYER));
        assertEquals(249000L, rawCart(BUYER)
                .getList("items", Document.class).getFirst()
                .getLong("unit_price_snapshot"));

        verify(productService, times(1)).getProduct("product-001");
        verify(inventoryClient, times(1)).fetchAvailability(Set.of("sku-001"));
    }

    @Test
    void shouldReturnProductUnavailableAndSkipInventory() throws Exception {
        seed(cart(BUYER, List.of(item("product-001", "sku-001"))));
        Document before = rawCart(BUYER);

        when(productService.getProduct("product-001"))
                .thenReturn(ProductHydrationResult.unavailable());

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("PRODUCT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].available").value(false))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot").value(249000))
                .andExpect(jsonPath("$.data.items[0].line_total").value(498000));

        assertEquals(before, rawCart(BUYER));
        verify(productService).getProduct("product-001");
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void shouldReturnStaleWhenProductCannotBeVerified() throws Exception {
        seed(cart(BUYER, List.of(item("product-001", "sku-001"))));
        Document before = rawCart(BUYER);

        when(productService.getProduct("product-001"))
                .thenReturn(ProductHydrationResult.stale());

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("STALE"))
                .andExpect(jsonPath("$.data.items[0].available").value(true))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot").value(249000))
                .andExpect(jsonPath("$.data.items[0].line_total").value(498000));

        assertEquals(before, rawCart(BUYER));
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void shouldReturnUnknownWhenInventoryFails() throws Exception {
        seed(cart(BUYER, List.of(item("product-001", "sku-001"))));
        Document before = rawCart(BUYER);

        when(productService.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));
        when(inventoryClient.fetchAvailability(Set.of("sku-001")))
                .thenThrow(new IllegalStateException("Inventory is unavailable"));

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].available").value(true))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot").value(229000))
                .andExpect(jsonPath("$.data.items[0].line_total").value(458000));

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldReturnOutOfStockWhenInventoryQuantityIsInsufficient()
            throws Exception {
        seed(cart(BUYER, List.of(item("product-001", "sku-001"))));

        when(productService.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));
        when(inventoryClient.fetchAvailability(Set.of("sku-001")))
                .thenReturn(Map.of("sku-001", stock("sku-001", 1)));

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data.items[0].available").value(false))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    void shouldFetchSameProductOnceForMultipleSkus() throws Exception {
        seed(cart(BUYER, List.of(
                item("product-001", "sku-001"),
                item("product-001", "sku-002"))));

        ProductHydrationResult result = ProductHydrationResult.found(Map.of(
                "sku-001", sku("product-001", "sku-001", 229000L),
                "sku-002", sku("product-001", "sku-002", 199000L)));

        when(productService.getProduct("product-001")).thenReturn(result);
        when(inventoryClient.fetchAvailability(Set.of("sku-001", "sku-002")))
                .thenReturn(Map.of(
                        "sku-001", stock("sku-001", 7),
                        "sku-002", stock("sku-002", 0)));

        Document before = rawCart(BUYER);

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_count").value(2))
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot").value(229000))
                .andExpect(jsonPath("$.data.items[1].availability_status")
                        .value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data.items[1].unit_price_snapshot").value(199000))
                .andExpect(jsonPath("$.data.items[1].line_total").value(398000));

        assertEquals(before, rawCart(BUYER));
        verify(productService, times(1)).getProduct("product-001");
        verify(inventoryClient, times(1))
                .fetchAvailability(Set.of("sku-001", "sku-002"));
    }

    @Test
    void shouldKeepProductsIndependentWhenOneProductIsUnavailable()
            throws Exception {
        seed(cart(BUYER, List.of(
                item("product-001", "sku-001"),
                item("product-002", "sku-002"))));

        when(productService.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));
        when(productService.getProduct("product-002"))
                .thenReturn(ProductHydrationResult.unavailable());
        when(inventoryClient.fetchAvailability(Set.of("sku-001")))
                .thenReturn(Map.of("sku-001", stock("sku-001", 10)));

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[1].availability_status")
                        .value("PRODUCT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[1].available").value(false));

        verify(inventoryClient).fetchAvailability(Set.of("sku-001"));
        verifyNoMoreInteractions(inventoryClient);
    }

    @Test
    void shouldIsolateBuyerCarts() throws Exception {
        seed(cart("buyer-A", List.of(item("product-A", "sku-A"))));
        seed(cart("buyer-B", List.of(item("product-B", "sku-B"))));

        when(productService.getProduct("product-A"))
                .thenReturn(found("product-A", "sku-A", 100000L));
        when(productService.getProduct("product-B"))
                .thenReturn(found("product-B", "sku-B", 200000L));
        when(inventoryClient.fetchAvailability(Set.of("sku-A")))
                .thenReturn(Map.of("sku-A", stock("sku-A", 5)));
        when(inventoryClient.fetchAvailability(Set.of("sku-B")))
                .thenReturn(Map.of("sku-B", stock("sku-B", 1)));

        mvc.perform(get(PATH).with(gateway("buyer-A"))
                        .param("buyer_user_id", "buyer-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value("cart-buyer-A"))
                .andExpect(jsonPath("$.data.items[0].sku_id").value("sku-A"))
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("AVAILABLE"));

        mvc.perform(get(PATH).with(gateway("buyer-B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value("cart-buyer-B"))
                .andExpect(jsonPath("$.data.items[0].sku_id").value("sku-B"))
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("OUT_OF_STOCK"));

        assertEquals(2L, countCarts());
    }

    @Test
    void shouldRejectUnauthenticatedBuyerWithoutHydration() throws Exception {
        seed(cart(BUYER, List.of(item("product-001", "sku-001"))));
        Document before = rawCart(BUYER);

        mvc.perform(get(PATH).header("X-User-ID", BUYER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_UNAUTHENTICATED"));

        assertEquals(before, rawCart(BUYER));
        verifyNoInteractions(productService, inventoryClient);
    }

    private RequestPostProcessor gateway(String buyer) {
        return request -> {
            request.addHeader("X-Gateway-Service-Token", GATEWAY_TOKEN);
            request.addHeader("X-User-ID", buyer);
            return request;
        };
    }

    private void seed(Cart cart) {
        mongoTemplate.insert(mapper.toDocument(cart));
    }

    private Cart cart(String buyer, List<CartItem> items) {
        return new Cart("cart-" + buyer, buyer, items, 3L, NOW, NOW);
    }

    private CartItem item(String productId, String skuId) {
        return new CartItem(
                "item-" + skuId, productId, skuId, "shop-001",
                2, 2L, "Old title", "Old label",
                249000L, true, NOW, NOW);
    }

    private ProductHydrationResult found(
            String productId, String skuId, long price) {
        return ProductHydrationResult.found(Map.of(
                skuId, sku(productId, skuId, price)));
    }

    private ValidatedSkuSnapshot sku(
            String productId, String skuId, long price) {
        return new ValidatedSkuSnapshot(
                productId, skuId, "shop-001",
                "Current title", "Black / L", price);
    }

    private InventoryStock stock(String skuId, long quantity) {
        return new InventoryStock(skuId, quantity, "ACTIVE", NOW);
    }

    private Document rawCart(String buyer) {
        Document document = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer)).first();
        assertNotNull(document);
        return document;
    }

    private long countCarts() {
        return mongoTemplate.getCollection("carts").countDocuments();
    }
}

package com.taca.cart.api;

import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.integrations.ProductHydrationClient;
import com.taca.cart.persistence.CartCasPersistence;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
class InternalCartGetHttpIntegrationTest {
    private static final String PATH = "/api/v1/internal/carts/buyer-001";
    private static final String BUYER = "buyer-001";
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_test"));
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

    @MockitoSpyBean
    private CartCasPersistence persistence;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @MockitoBean
    private ProductHydrationClient productHydrationClient;

    @MockitoBean
    private InventoryAvailabilityClient inventoryClient;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys").deleteMany(new Document());
    }

    @Test
    void shouldReturnEmptyCartWithoutCreatingDocument() throws Exception {
        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .header("X-Request-ID", "req-internal-empty"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-internal-empty"))
                .andExpect(jsonPath("$.meta.request_id").value("req-internal-empty"))
                .andExpect(jsonPath("$.data.cart_id").value(nullValue()))
                .andExpect(jsonPath("$.data.buyer_user_id").value(BUYER))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.missing_item_ids").isEmpty());

        assertEquals(0L, countCarts());
        verify(persistence).findByBuyerUserId(BUYER);
        assertNoExternalCalls();
    }

    @Test
    void shouldReturnRequestedItemsAsMissingWhenCartDoesNotExist()
            throws Exception {
        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("item_ids", "item-001,item-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value(nullValue()))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.missing_item_ids", hasSize(2)))
                .andExpect(jsonPath("$.data.missing_item_ids[0]").value("item-001"))
                .andExpect(jsonPath("$.data.missing_item_ids[1]").value("item-002"));

        assertEquals(0L, countCarts());
        assertNoExternalCalls();
    }

    @Test
    void shouldReturnPersistedEmptyCartWithoutWriting() throws Exception {
        seed(cart(BUYER, List.of()));
        Document before = rawCart(BUYER);

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value("cart-buyer-001"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.missing_item_ids").isEmpty());

        assertEquals(before, rawCart(BUYER));
        assertNoExternalCalls();
    }

    @Test
    void shouldReturnAllItemsWithPersistedRevisionsAndPrices()
            throws Exception {
        seed(cart(BUYER, List.of(
                item("001", 2, 3L, 249000L),
                item("002", 4, 5L, 199000L))));
        Document before = rawCart(BUYER);

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .header("X-Request-ID", "req-all-items"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.request_id").value("req-all-items"))
                .andExpect(jsonPath("$.data.cart_id").value("cart-buyer-001"))
                .andExpect(jsonPath("$.data.buyer_user_id").value(BUYER))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].item_id").value("item-001"))
                .andExpect(jsonPath("$.data.items[0].product_id").value("product-001"))
                .andExpect(jsonPath("$.data.items[0].sku_id").value("sku-001"))
                .andExpect(jsonPath("$.data.items[0].shop_id").value("shop-001"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].item_revision").value(3))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot").value(249000))
                .andExpect(jsonPath("$.data.items[1].item_id").value("item-002"))
                .andExpect(jsonPath("$.data.items[1].quantity").value(4))
                .andExpect(jsonPath("$.data.items[1].item_revision").value(5))
                .andExpect(jsonPath("$.data.items[1].unit_price_snapshot").value(199000))
                .andExpect(jsonPath("$.data.missing_item_ids").isEmpty())
                .andExpect(jsonPath("$.data.items[0].availability_status").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].line_total").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist());

        assertEquals(before, rawCart(BUYER));
        assertNoExternalCalls();
    }

    @Test
    void shouldFilterItemsDeduplicateAndPreserveRequestedOrder()
            throws Exception {
        seed(cart(BUYER, List.of(
                item("001", 2, 3L, 249000L),
                item("002", 1, 2L, 199000L))));
        Document before = rawCart(BUYER);

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("item_ids", "item-002,item-404,item-001,item-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].item_id").value("item-002"))
                .andExpect(jsonPath("$.data.items[0].item_revision").value(2))
                .andExpect(jsonPath("$.data.items[1].item_id").value("item-001"))
                .andExpect(jsonPath("$.data.items[1].item_revision").value(3))
                .andExpect(jsonPath("$.data.missing_item_ids", hasSize(1)))
                .andExpect(jsonPath("$.data.missing_item_ids[0]").value("item-404"));

        assertEquals(before, rawCart(BUYER));
        assertNoExternalCalls();
    }

    @Test
    void shouldReturnMissingIdsWhenRequestedItemsBelongToAnotherBuyer()
            throws Exception {
        seed(cart("buyer-001", List.of(item("001", 2, 3L, 249000L))));
        seed(cart("buyer-002", List.of(item("002", 3, 4L, 199000L))));

        Document buyerOneBefore = rawCart("buyer-001");
        Document buyerTwoBefore = rawCart("buyer-002");

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("item_ids", "item-002,item-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buyer_user_id").value("buyer-001"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].item_id").value("item-001"))
                .andExpect(jsonPath("$.data.missing_item_ids", hasSize(1)))
                .andExpect(jsonPath("$.data.missing_item_ids[0]").value("item-002"));

        assertEquals(buyerOneBefore, rawCart("buyer-001"));
        assertEquals(buyerTwoBefore, rawCart("buyer-002"));
        assertNoExternalCalls();
    }

    @Test
    void shouldReadBuyerFromPathNotSpoofedQueryOrHeaders()
            throws Exception {
        seed(cart("buyer-001", List.of(item("001", 2, 3L, 249000L))));
        seed(cart("buyer-002", List.of(item("002", 3, 4L, 199000L))));

        mvc.perform(get("/api/v1/internal/carts/buyer-002")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .header("X-User-ID", "buyer-001")
                        .param("buyer_user_id", "buyer-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value("cart-buyer-002"))
                .andExpect(jsonPath("$.data.buyer_user_id").value("buyer-002"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].sku_id").value("sku-002"));

        assertEquals(2L, countCarts());
        assertNoExternalCalls();
    }

    @Test
    void shouldAcceptExactlyHundredRequestedItems() throws Exception {
        List<CartItem> items = IntStream.range(0, 100)
                .mapToObj(i -> item("%03d".formatted(i), 1, 1L, 1000L))
                .toList();

        seed(cart(BUYER, items));
        Document before = rawCart(BUYER);

        String itemIds = IntStream.range(0, 100)
                .mapToObj(i -> "item-%03d".formatted(i))
                .collect(java.util.stream.Collectors.joining(","));

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("item_ids", itemIds))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(100)))
                .andExpect(jsonPath("$.data.items[0].item_id").value("item-000"))
                .andExpect(jsonPath("$.data.items[99].item_id").value("item-099"))
                .andExpect(jsonPath("$.data.missing_item_ids").isEmpty());

        assertEquals(before, rawCart(BUYER));
        assertNoExternalCalls();
    }

    @Test
    void shouldRejectInvalidItemIdsWith400() throws Exception {
        for (String input : List.of(
                "", " ", ",", "item-001,", ",item-002",
                "item-001,,item-002", "item-001,../admin")) {
            mvc.perform(get(PATH)
                            .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                            .param("item_ids", input))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value("CART_INVALID_INPUT"));
        }

        assertEquals(0L, countCarts());
        verify(persistence, never()).findByBuyerUserId(anyString());
    }

    @Test
    void shouldRejectHundredAndFirstRequestedItemId() throws Exception {
        String itemIds = IntStream.range(0, 101)
                .mapToObj(i -> "item-" + i)
                .collect(java.util.stream.Collectors.joining(","));

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("item_ids", itemIds))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_INVALID_INPUT"));

        verify(persistence, never()).findByBuyerUserId(anyString());
    }

    @Test
    void shouldRejectMalformedBuyerId() throws Exception {
        mvc.perform(get("/api/v1/internal/carts/bad@buyer")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_INVALID_INPUT"));

        verify(persistence, never()).findByBuyerUserId(anyString());
    }

    @Test
    void shouldRejectMissingInternalToken() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_UNAUTHENTICATED"));

        verify(persistence, never()).findByBuyerUserId(anyString());
    }

    @Test
    void shouldRejectInvalidInternalTokens() throws Exception {
        for (String token : List.of(
                "c".repeat(64), "b".repeat(63), "not-a-token")) {
            mvc.perform(get(PATH)
                            .header("X-Internal-Service-Token", token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code")
                            .value("CART_UNAUTHENTICATED"));
        }

        verify(persistence, never()).findByBuyerUserId(anyString());
    }

    @Test
    void shouldRejectDuplicateInternalTokenHeaders() throws Exception {
        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token",
                                INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());

        verify(persistence, never()).findByBuyerUserId(anyString());
    }

    @Test
    void shouldRejectGatewayBuyerTokenOnInternalRoute() throws Exception {
        mvc.perform(get(PATH)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER))
                .andExpect(status().isUnauthorized());

        verify(persistence, never()).findByBuyerUserId(anyString());
    }

    @Test
    void shouldReturn503WhenMongoQueryFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("MongoDB unavailable"))
                .when(persistence).findByBuyerUserId(BUYER);

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"));

        verify(persistence).findByBuyerUserId(BUYER);
        assertNoExternalCalls();
    }

    @Test
    void shouldRemainReadOnlyAcrossRepeatedInternalGets() throws Exception {
        seed(cart(BUYER, List.of(item("001", 2, 3L, 249000L))));
        Document before = rawCart(BUYER);

        for (int i = 0; i < 3; i++) {
            mvc.perform(get(PATH)
                            .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].item_revision").value(3))
                    .andExpect(jsonPath("$.data.items[0].quantity").value(2));
        }

        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, countCarts());

        verify(persistence, times(3)).findByBuyerUserId(BUYER);
        verify(persistence, never()).mutate(eq(BUYER), any());
        assertNoExternalCalls();
    }

    private void seed(Cart cart) {
        mongoTemplate.insert(mapper.toDocument(cart));
    }

    private Cart cart(String buyer, List<CartItem> items) {
        return new Cart("cart-" + buyer, buyer, items, 3L, NOW, NOW);
    }

    private CartItem item(String suffix, int quantity,
                          long revision, long price) {
        return new CartItem(
                "item-" + suffix,
                "product-" + suffix,
                "sku-" + suffix,
                "shop-001",
                quantity,
                revision,
                "Stored title " + suffix,
                "Stored label " + suffix,
                price,
                true,
                NOW,
                NOW);
    }

    private Document rawCart(String buyer) {
        Document document = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer))
                .first();

        assertNotNull(document);
        return document;
    }

    private long countCarts() {
        return mongoTemplate.getCollection("carts").countDocuments();
    }

    private void assertNoExternalCalls() {
        verifyNoInteractions(
                productCatalogClient,
                productHydrationClient,
                inventoryClient);
    }
}

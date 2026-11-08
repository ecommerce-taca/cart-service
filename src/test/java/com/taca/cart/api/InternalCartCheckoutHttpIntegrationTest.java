package com.taca.cart.api;

import com.taca.cart.api.dto.CheckedOutCartRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
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
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "cart.indexes.initialize=false"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InternalCartCheckoutHttpIntegrationTest {
    private static final String BASE = "/api/v1/internal/carts";
    private static final String BUYER = "buyer-001";
    private static final String CART_ID = "cart-buyer-001";
    private static final String PATH = BASE + "/" + BUYER + "/checked-out";
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    void shouldRemoveMatchingItemsAndReturnContractEnvelope() throws Exception {
        seed(cart(BUYER, defaultItems()));

        postCheckedOut(BUYER, body(CART_ID,
                List.of("item-003", "item-001"),
                Map.of("item-003", 1L, "item-001", 3L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.cart_id").value(CART_ID))
                .andExpect(jsonPath("$.data.removed_item_ids", hasSize(2)))
                .andExpect(jsonPath("$.data.removed_item_ids[0]").value("item-003"))
                .andExpect(jsonPath("$.data.removed_item_ids[1]").value("item-001"))
                .andExpect(jsonPath("$.data.missing_item_ids").isEmpty())
                .andExpect(jsonPath("$.data.skipped_modified_item_ids").isEmpty())
                .andExpect(jsonPath("$.data.remaining_item_count").value(1))
                .andExpect(jsonPath("$.data.version").doesNotExist());

        Document persisted = rawCart(BUYER);
        assertEquals(6L, persisted.getLong("version"));
        assertEquals(1, storedItems(BUYER).size());
        assertEquals("item-002", storedItems(BUYER).getFirst().getString("item_id"));
        assertEquals(5, storedItems(BUYER).getFirst().getInteger("quantity"));
        assertEquals(2L, storedItems(BUYER).getFirst().getLong("item_revision"));
        assertNoExternalCalls();
    }

    @Test
    void shouldPropagateRequestIdAndReturn200() throws Exception {
        seed(cart(BUYER, defaultItems()));

        mvc.perform(post(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .header("X-Request-ID", "req-checkout-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CART_ID, List.of("item-001"),
                                Map.of("item-001", 3L))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", "req-checkout-001"))
                .andExpect(jsonPath("$.meta.request_id").value("req-checkout-001"));

        assertEquals(6L, rawCart(BUYER).getLong("version"));
    }

    @Test
    void shouldClassifyMissingModifiedAndRemovedInRequestOrder()
            throws Exception {
        seed(cart(BUYER, defaultItems()));

        postCheckedOut(BUYER, body(CART_ID,
                List.of("item-404", "item-002", "item-001"),
                Map.of("item-404", 1L, "item-002", 1L, "item-001", 3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed_item_ids[0]").value("item-001"))
                .andExpect(jsonPath("$.data.missing_item_ids[0]").value("item-404"))
                .andExpect(jsonPath("$.data.skipped_modified_item_ids[0]")
                        .value("item-002"))
                .andExpect(jsonPath("$.data.remaining_item_count").value(2));

        assertEquals(List.of("item-002", "item-003"),
                storedItems(BUYER).stream()
                        .map(item -> item.getString("item_id")).toList());
        assertEquals(5, storedItems(BUYER).getFirst().getInteger("quantity"));
        assertEquals(2L, storedItems(BUYER).getFirst().getLong("item_revision"));
        assertEquals(6L, rawCart(BUYER).getLong("version"));
        assertNoExternalCalls();
    }

    @Test
    void shouldNotWriteWhenAllRevisionsMismatch() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        postCheckedOut(BUYER, body(CART_ID,
                List.of("item-002"), Map.of("item-002", 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed_item_ids").isEmpty())
                .andExpect(jsonPath("$.data.missing_item_ids").isEmpty())
                .andExpect(jsonPath("$.data.skipped_modified_item_ids[0]")
                        .value("item-002"))
                .andExpect(jsonPath("$.data.remaining_item_count").value(3));

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldNotWriteWhenEveryRequestedItemIsMissing() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        postCheckedOut(BUYER, body(CART_ID,
                List.of("item-404"), Map.of("item-404", 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed_item_ids").isEmpty())
                .andExpect(jsonPath("$.data.missing_item_ids[0]").value("item-404"))
                .andExpect(jsonPath("$.data.skipped_modified_item_ids").isEmpty())
                .andExpect(jsonPath("$.data.remaining_item_count").value(3));

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldReplayCleanupWithoutDeletingAgain() throws Exception {
        seed(cart(BUYER, defaultItems()));
        String request = body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L));

        postCheckedOut(BUYER, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed_item_ids[0]").value("item-001"));

        Document afterFirst = rawCart(BUYER);

        postCheckedOut(BUYER, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed_item_ids").isEmpty())
                .andExpect(jsonPath("$.data.missing_item_ids[0]").value("item-001"))
                .andExpect(jsonPath("$.data.remaining_item_count").value(2));

        assertEquals(afterFirst, rawCart(BUYER));
        assertEquals(6L, rawCart(BUYER).getLong("version"));
    }

    @Test
    void shouldAllowRemovingAllHundredItemsWithinOneMutation()
            throws Exception {
        List<CartItem> items = IntStream.range(0, 100)
                .mapToObj(i -> item("%03d".formatted(i), 1, 1L))
                .toList();

        seed(cart(BUYER, items));

        List<String> ids = items.stream().map(CartItem::itemId).toList();
        Map<String, Long> revisions = new LinkedHashMap<>();
        ids.forEach(id -> revisions.put(id, 1L));

        postCheckedOut(BUYER, body(CART_ID, ids, revisions))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed_item_ids", hasSize(100)))
                .andExpect(jsonPath("$.data.remaining_item_count").value(0));

        assertTrue(storedItems(BUYER).isEmpty());
        assertEquals(6L, rawCart(BUYER).getLong("version"));
        assertEquals(1L, countCarts());
    }

    @Test
    void shouldReturn404WithoutCreatingMissingCart() throws Exception {
        postCheckedOut(BUYER, body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));

        assertEquals(0L, countCarts());
    }

    @Test
    void shouldRejectDifferentCartIdWithoutChangingMongo()
            throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        postCheckedOut(BUYER, body("cart-other",
                List.of("item-001"), Map.of("item-001", 3L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldNotAffectAnotherBuyerEvenWithTheirCartId()
            throws Exception {
        seed(cart(BUYER, defaultItems()));
        seed(cart("buyer-002", List.of(item("004", 2, 1L))));

        Document beforeA = rawCart(BUYER);
        Document beforeB = rawCart("buyer-002");

        postCheckedOut("buyer-002", body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));

        assertEquals(beforeA, rawCart(BUYER));
        assertEquals(beforeB, rawCart("buyer-002"));
    }

    @Test
    void shouldRejectInvalidCleanupContractsWith400() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        List<String> invalidRequests = List.of(
                body(CART_ID, List.of(), Map.of()),
                body(CART_ID, List.of("item-001", "item-001"),
                        Map.of("item-001", 3L)),
                body(CART_ID, List.of("item-001"), Map.of()),
                body(CART_ID, List.of("item-001"),
                        Map.of("item-001", 3L, "item-002", 2L)),
                body(CART_ID, List.of("item-001", "item-002"),
                        Map.of("item-001", 3L, "item-003", 1L)),
                body(CART_ID, List.of("item-001"),
                        Map.of("item-001", 0L)),
                body(CART_ID, List.of("item-001"),
                        Map.of("item-001", -1L)),
                body("bad@cart", List.of("item-001"),
                        Map.of("item-001", 3L))
        );

        for (String request : invalidRequests) {
            postCheckedOut(BUYER, request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value("CART_INVALID_INPUT"));
        }

        assertEquals(before, rawCart(BUYER));
        verify(persistence, never()).mutate(eq(BUYER), any());
    }

    @Test
    void shouldRejectMissingRevisionFieldWith400() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        postCheckedOut(BUYER, """
                {"cart_id":"cart-buyer-001",
                 "checkout_group_id":"group-001",
                 "item_ids":["item-001"]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_INVALID_INPUT"));

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldRejectMalformedJsonWith400() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        postCheckedOut(BUYER, "{bad json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_INVALID_INPUT"));

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldRejectMalformedBuyerIdBeforeStorage() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        postCheckedOut("bad@buyer",
                body(CART_ID, List.of("item-001"), Map.of("item-001", 3L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_INVALID_INPUT"));

        assertEquals(before, rawCart(BUYER));
        verify(persistence, never()).mutate(anyString(), any());
    }

    @Test
    void shouldRejectMissingInternalTokenBeforeStorage() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CART_ID, List.of("item-001"),
                                Map.of("item-001", 3L))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_UNAUTHENTICATED"));

        assertEquals(before, rawCart(BUYER));
        verify(persistence, never()).mutate(eq(BUYER), any());
    }

    @Test
    void shouldRejectInvalidAndDuplicateInternalTokens()
            throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);
        String request = body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L));

        mvc.perform(post(PATH)
                        .header("X-Internal-Service-Token", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(PATH)
                        .header("X-Internal-Service-Token",
                                INTERNAL_TOKEN, INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized());

        assertEquals(before, rawCart(BUYER));
        verify(persistence, never()).mutate(eq(BUYER), any());
    }

    @Test
    void shouldRejectBuyerGatewayTokenOnCheckoutRoute()
            throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        mvc.perform(post(PATH)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CART_ID, List.of("item-001"),
                                Map.of("item-001", 3L))))
                .andExpect(status().isUnauthorized());

        assertEquals(before, rawCart(BUYER));
        verify(persistence, never()).mutate(eq(BUYER), any());
    }

    @Test
    void shouldMapMongoFailureTo503() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        doThrow(new DataAccessResourceFailureException("MongoDB unavailable"))
                .when(persistence).mutate(eq(BUYER), any());

        postCheckedOut(BUYER, body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"));

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldMapCasRetryExhaustionTo409() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        doThrow(new CartException(CartErrorCode.CART_VERSION_CONFLICT,
                "Cart was modified concurrently"))
                .when(persistence).mutate(eq(BUYER), any());

        postCheckedOut(BUYER, body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_VERSION_CONFLICT"));

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldReflectCleanupInSubsequentInternalGet()
            throws Exception {
        seed(cart(BUYER, defaultItems()));

        postCheckedOut(BUYER, body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L)))
                .andExpect(status().isOk());

        mvc.perform(get(BASE + "/" + BUYER)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].item_id").value("item-002"))
                .andExpect(jsonPath("$.data.items[0].item_revision").value(2))
                .andExpect(jsonPath("$.data.items[1].item_id").value("item-003"))
                .andExpect(jsonPath("$.data.missing_item_ids").isEmpty());

        assertNoExternalCalls();
    }

    @Test
    void shouldRejectMissingRequestBodyWith400() throws Exception {
        seed(cart(BUYER, defaultItems()));
        Document before = rawCart(BUYER);

        mvc.perform(post(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_INVALID_INPUT"));

        assertEquals(before, rawCart(BUYER));
        verify(persistence, never()).mutate(eq(BUYER), any());
    }

    private ResultActions postCheckedOut(
            String buyer, String json) throws Exception {
        return mvc.perform(post(BASE + "/" + buyer + "/checked-out")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String body(
            String cartId,
            List<String> ids,
            Map<String, Long> revisions) throws Exception {
        return JSON.writeValueAsString(new CheckedOutCartRequest(
                cartId, "group-001", ids, revisions));
    }

    private void seed(Cart cart) {
        mongoTemplate.insert(mapper.toDocument(cart));
    }

    private Cart cart(String buyer, List<CartItem> items) {
        return new Cart("cart-" + buyer, buyer, items, 5L, NOW, NOW);
    }

    private List<CartItem> defaultItems() {
        return List.of(
                item("001", 2, 3L),
                item("002", 5, 2L),
                item("003", 1, 1L));
    }

    private CartItem item(String suffix, int qty, long revision) {
        return new CartItem(
                "item-" + suffix,
                "product-" + suffix,
                "sku-" + suffix,
                "shop-001",
                qty,
                revision,
                "Stored title",
                "Stored label",
                249000L,
                true,
                NOW,
                NOW);
    }

    private Document rawCart(String buyer) {
        Document cart = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer))
                .first();
        assertNotNull(cart);
        return cart;
    }

    private List<Document> storedItems(String buyer) {
        return rawCart(buyer).getList("items", Document.class);
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

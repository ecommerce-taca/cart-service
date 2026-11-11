package com.taca.cart.contracts;

import com.taca.cart.api.dto.CheckedOutCartRequest;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.integrations.ProductHydrationClient;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
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
class CheckoutCartHttpContractTest {
    private static final String BASE = "/api/v1/internal/carts";
    private static final String BUYER = "buyer-001";
    private static final String CART_ID = "cart-buyer-001";
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7.0").withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_checkout_contract_test"));
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
    private ProductCatalogClient productCatalogClient;

    @MockitoBean
    private ProductHydrationClient productHydrationClient;

    @MockitoBean
    private InventoryAvailabilityClient inventoryClient;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys")
                .deleteMany(new Document());
    }

    @AfterEach
    void verifyInternalApisDoNotCallExternalDependencies() {
        verifyNoInteractions(productCatalogClient,
                productHydrationClient, inventoryClient);
    }

    @Test
    void shouldReturnCheckoutReadableSnapshotWithItemRevisions()
            throws Exception {
        seed(CART_ID, BUYER, defaultItems());
        Document before = rawCart(BUYER);

        MvcResult result = internalGet(BUYER, null, "req-checkout-get");

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("no-store",
                result.getResponse().getHeader("Cache-Control"));
        assertEquals("req-checkout-get",
                result.getResponse().getHeader("X-Request-ID"));

        JsonNode root = json(result);
        JsonNode data = root.path("data");

        assertExactFields(root, "data", "meta");
        assertExactFields(data, "cart_id", "buyer_user_id",
                "items", "missing_item_ids");
        assertExactFields(root.path("meta"), "request_id");

        assertEquals(CART_ID, data.path("cart_id").asString());
        assertEquals(BUYER, data.path("buyer_user_id").asString());
        assertEquals(3, data.path("items").size());
        assertTrue(data.path("missing_item_ids").isEmpty());

        JsonNode first = data.path("items").get(0);
        assertExactFields(first, "item_id", "product_id", "sku_id",
                "shop_id", "quantity", "item_revision",
                "unit_price_snapshot");

        assertEquals("item-001", first.path("item_id").asString());
        assertEquals(2, first.path("quantity").asInt());
        assertEquals(3L, first.path("item_revision").asLong());
        assertEquals(249000L,
                first.path("unit_price_snapshot").asLong());

        assertEquals("req-checkout-get",
                root.path("meta").path("request_id").asString());
        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldPreserveRequestedOrderAndReportMissingItems()
            throws Exception {
        seed(CART_ID, BUYER, defaultItems());
        Document before = rawCart(BUYER);

        MvcResult result = internalGet(BUYER,
                "item-003,item-001,item-missing,item-001", "req-filter");

        assertEquals(200, result.getResponse().getStatus());

        JsonNode data = json(result).path("data");
        assertEquals(2, data.path("items").size());
        assertEquals("item-003",
                data.path("items").get(0).path("item_id").asString());
        assertEquals("item-001",
                data.path("items").get(1).path("item_id").asString());
        assertEquals(1L,
                data.path("items").get(0).path("item_revision").asLong());
        assertEquals(3L,
                data.path("items").get(1).path("item_revision").asLong());
        assertEquals("item-missing",
                data.path("missing_item_ids").get(0).asString());

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldCompleteGetCleanupGetContractFlow()
            throws Exception {
        seed(CART_ID, BUYER, defaultItems());

        MvcResult snapshot = internalGet(BUYER,
                "item-003,item-001", "req-snapshot");
        JsonNode data = json(snapshot).path("data");

        List<String> ids = new ArrayList<>();
        Map<String, Long> revisions = new LinkedHashMap<>();

        for (JsonNode item : data.path("items")) {
            String id = item.path("item_id").asString();
            ids.add(id);
            revisions.put(id, item.path("item_revision").asLong());
        }

        assertEquals(List.of("item-003", "item-001"), ids);
        assertEquals(Map.of("item-003", 1L, "item-001", 3L),
                revisions);

        MvcResult cleanup = internalCleanup(BUYER,
                body(data.path("cart_id").asString(), ids, revisions),
                "req-cleanup");

        assertEquals(200, cleanup.getResponse().getStatus());
        assertEquals("no-store",
                cleanup.getResponse().getHeader("Cache-Control"));
        assertEquals("req-cleanup",
                cleanup.getResponse().getHeader("X-Request-ID"));

        JsonNode root = json(cleanup);
        JsonNode result = root.path("data");

        assertExactFields(root, "data", "meta");
        assertExactFields(result, "cart_id", "removed_item_ids",
                "missing_item_ids", "skipped_modified_item_ids",
                "remaining_item_count");

        assertEquals(CART_ID, result.path("cart_id").asString());
        assertEquals("item-003",
                result.path("removed_item_ids").get(0).asString());
        assertEquals("item-001",
                result.path("removed_item_ids").get(1).asString());
        assertTrue(result.path("missing_item_ids").isEmpty());
        assertTrue(result.path("skipped_modified_item_ids").isEmpty());
        assertEquals(1, result.path("remaining_item_count").asInt());
        assertEquals("req-cleanup",
                root.path("meta").path("request_id").asString());

        MvcResult after = internalGet(BUYER, null, "req-after");
        JsonNode remaining = json(after).path("data");

        assertEquals(1, remaining.path("items").size());
        assertEquals("item-002",
                remaining.path("items").get(0).path("item_id").asString());
        assertEquals(2L,
                remaining.path("items").get(0)
                        .path("item_revision").asLong());
        assertEquals(6L, rawCart(BUYER).getLong("version"));
    }

    @Test
    void shouldPreserveItemModifiedAfterCheckoutSnapshot()
            throws Exception {
        seed(CART_ID, BUYER, defaultItems());

        MvcResult snapshot = internalGet(BUYER,
                "item-002,item-003", "req-before-update");

        JsonNode items = json(snapshot).path("data").path("items");
        Map<String, Long> expected = new LinkedHashMap<>();

        for (JsonNode item : items) {
            expected.put(item.path("item_id").asString(),
                    item.path("item_revision").asLong());
        }

        mvc.perform(put("/api/v1/cart/items/item-002")
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(6));

        assertEquals(6L, rawCart(BUYER).getLong("version"));

        MvcResult cleanup = internalCleanup(BUYER,
                body(CART_ID, List.of("item-002", "item-003"), expected),
                "req-race-cleanup");

        assertEquals(200, cleanup.getResponse().getStatus());

        JsonNode data = json(cleanup).path("data");
        assertEquals(1, data.path("removed_item_ids").size());
        assertEquals("item-003",
                data.path("removed_item_ids").get(0).asString());
        assertEquals(1, data.path("skipped_modified_item_ids").size());
        assertEquals("item-002",
                data.path("skipped_modified_item_ids").get(0).asString());
        assertEquals(2, data.path("remaining_item_count").asInt());

        List<Document> itemsAfter = storedItems(BUYER);
        assertEquals(2, itemsAfter.size());
        assertEquals("item-002",
                itemsAfter.get(1).getString("item_id"));
        assertEquals(6, itemsAfter.get(1).getInteger("quantity"));
        assertEquals(3L,
                itemsAfter.get(1).getLong("item_revision"));
        assertEquals(7L, rawCart(BUYER).getLong("version"));
    }

    @Test
    void shouldRetryCleanupWithoutPerformingAnotherMutation()
            throws Exception {
        seed(CART_ID, BUYER, defaultItems());

        String request = body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L));

        MvcResult first = internalCleanup(BUYER, request, "req-first");
        assertEquals(200, first.getResponse().getStatus());
        assertEquals("item-001",
                json(first).path("data").path("removed_item_ids")
                        .get(0).asString());

        Document afterFirst = rawCart(BUYER);

        MvcResult retry = internalCleanup(BUYER, request, "req-retry");
        assertEquals(200, retry.getResponse().getStatus());

        JsonNode retryData = json(retry).path("data");
        assertTrue(retryData.path("removed_item_ids").isEmpty());
        assertEquals("item-001",
                retryData.path("missing_item_ids").get(0).asString());
        assertTrue(retryData.path("skipped_modified_item_ids").isEmpty());
        assertEquals(2, retryData.path("remaining_item_count").asInt());

        assertEquals(afterFirst, rawCart(BUYER));
        assertEquals(6L, rawCart(BUYER).getLong("version"));
    }

    @Test
    void shouldAcceptVersionOneCheckoutRequestFixture()
            throws Exception {
        seed("cart-001", BUYER, List.of(item("001", 2, 7L)));

        String fixture = readFixture("checked-out-request-v1.json");
        CheckedOutCartRequest parsed = JSON.readValue(
                fixture, CheckedOutCartRequest.class);

        assertEquals("cart-001", parsed.cartId());
        assertEquals("group-001", parsed.checkoutGroupId());
        assertEquals(List.of("item-001"), parsed.itemIds());
        assertEquals(Map.of("item-001", 7L),
                parsed.expectedItemRevisions());

        MvcResult result = internalCleanup(BUYER, fixture, "req-fixture");

        assertEquals(200, result.getResponse().getStatus());

        JsonNode data = json(result).path("data");
        assertEquals("cart-001", data.path("cart_id").asString());
        assertEquals("item-001",
                data.path("removed_item_ids").get(0).asString());
        assertTrue(data.path("missing_item_ids").isEmpty());
        assertTrue(data.path("skipped_modified_item_ids").isEmpty());
        assertEquals(0, data.path("remaining_item_count").asInt());

        assertTrue(storedItems(BUYER).isEmpty());
    }

    @Test
    void shouldRejectInvalidRevisionMapsWithoutMutation()
            throws Exception {
        seed(CART_ID, BUYER, defaultItems());
        Document before = rawCart(BUYER);

        List<String> invalidBodies = List.of(
                """
                {"cart_id":"cart-buyer-001","checkout_group_id":"group-001",
                 "item_ids":["item-001"]}
                """,
                body(CART_ID, List.of("item-001"), Map.of()),
                body(CART_ID, List.of("item-001", "item-001"),
                        Map.of("item-001", 3L)),
                body(CART_ID, List.of("item-001"),
                        Map.of("item-001", 0L)),
                body(CART_ID, List.of("item-001"),
                        Map.of("item-002", 2L))
        );

        for (String request : invalidBodies) {
            MvcResult result = internalCleanup(BUYER, request, "req-invalid");

            assertEquals(400, result.getResponse().getStatus());
            assertError(result, "CART_INVALID_INPUT");
        }

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldRejectCartIdAndBuyerMismatchWithoutMutation()
            throws Exception {
        seed(CART_ID, BUYER, defaultItems());
        Document before = rawCart(BUYER);

        MvcResult wrongCart = internalCleanup(BUYER,
                body("cart-other", List.of("item-001"),
                        Map.of("item-001", 3L)), "req-wrong-cart");

        assertEquals(404, wrongCart.getResponse().getStatus());
        assertError(wrongCart, "CART_ITEM_NOT_FOUND");

        MvcResult wrongBuyer = internalCleanup("buyer-002",
                body(CART_ID, List.of("item-001"),
                        Map.of("item-001", 3L)), "req-wrong-buyer");

        assertEquals(404, wrongBuyer.getResponse().getStatus());
        assertError(wrongBuyer, "CART_ITEM_NOT_FOUND");

        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, mongoTemplate.getCollection("carts").countDocuments());
    }

    @Test
    void shouldRequireCheckoutInternalAuthentication()
            throws Exception {
        seed(CART_ID, BUYER, defaultItems());
        Document before = rawCart(BUYER);
        String request = body(CART_ID,
                List.of("item-001"), Map.of("item-001", 3L));

        MvcResult missingToken = mvc.perform(
                        post(cleanupPath(BUYER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertError(missingToken, "CART_UNAUTHENTICATED");

        MvcResult gatewayOnly = mvc.perform(
                        post(cleanupPath(BUYER))
                                .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                                .header("X-User-ID", BUYER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertError(gatewayOnly, "CART_UNAUTHENTICATED");

        mvc.perform(post(cleanupPath(BUYER))
                        .header("X-Internal-Service-Token",
                                INTERNAL_TOKEN, INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized());

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldHandleMissingCartWithoutCreatingDocument()
            throws Exception {
        MvcResult getResult = internalGet(BUYER,
                "item-001,item-002", "req-missing-cart");

        assertEquals(200, getResult.getResponse().getStatus());
        JsonNode data = json(getResult).path("data");

        assertTrue(data.path("cart_id").isNull());
        assertTrue(data.path("items").isEmpty());
        assertEquals(2, data.path("missing_item_ids").size());
        assertEquals("item-001",
                data.path("missing_item_ids").get(0).asString());
        assertEquals("item-002",
                data.path("missing_item_ids").get(1).asString());

        MvcResult cleanup = internalCleanup(BUYER,
                body(CART_ID, List.of("item-001"),
                        Map.of("item-001", 3L)), "req-missing-cleanup");

        assertEquals(404, cleanup.getResponse().getStatus());
        assertError(cleanup, "CART_ITEM_NOT_FOUND");
        assertEquals(0L, mongoTemplate.getCollection("carts").countDocuments());
    }

    private MvcResult internalGet(String buyer, String itemIds,
                                  String requestId) throws Exception {
        var builder = get(BASE + "/" + buyer)
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .header("X-Request-ID", requestId);

        if (itemIds != null) builder.param("item_ids", itemIds);

        return mvc.perform(builder).andReturn();
    }

    private MvcResult internalCleanup(String buyer, String body,
                                      String requestId) throws Exception {
        return mvc.perform(post(cleanupPath(buyer))
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .header("X-Request-ID", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String cleanupPath(String buyer) {
        return BASE + "/" + buyer + "/checked-out";
    }

    private String body(String cartId, List<String> ids,
                        Map<String, Long> revisions) {
        return JSON.writeValueAsString(new CheckedOutCartRequest(
                cartId, "group-001", ids, revisions));
    }

    private void seed(String cartId, String buyer, List<CartItem> items) {
        Cart cart = new Cart(cartId, buyer, items, 5L, NOW, NOW);
        mongoTemplate.insert(mapper.toDocument(cart));
    }

    private List<CartItem> defaultItems() {
        return List.of(
                item("001", 2, 3L),
                item("002", 5, 2L),
                item("003", 1, 1L));
    }

    private CartItem item(String suffix, int quantity, long revision) {
        return new CartItem(
                "item-" + suffix, "product-" + suffix,
                "sku-" + suffix, "shop-001",
                quantity, revision,
                "Stored title", "Stored label",
                249000L, true, NOW, NOW);
    }

    private Document rawCart(String buyer) {
        Document cart = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer)).first();
        assertNotNull(cart);
        return cart;
    }

    private List<Document> storedItems(String buyer) {
        return rawCart(buyer).getList("items", Document.class);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private void assertExactFields(JsonNode node, String... fields) {
        assertTrue(node.isObject(), "Expected JSON object");
        assertEquals(fields.length, node.size(), "Unexpected JSON fields");
        for (String field : fields) {
            assertTrue(node.has(field), "Missing field: " + field);
        }
    }

    private void assertError(MvcResult result, String expectedCode) throws Exception {
        JsonNode root = json(result);
        assertExactFields(root, "error");

        JsonNode error = root.path("error");
        assertExactFields(error, "code", "message", "details", "trace_id");

        assertEquals(expectedCode, error.path("code").asString());
        assertFalse(error.path("message").asString().isBlank());
        assertTrue(error.path("details").isObject());
    }

    private String readFixture(String filename) throws Exception {
        String path = "contracts/cart/" + filename;

        try (InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(path),
                "Missing fixture: " + path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

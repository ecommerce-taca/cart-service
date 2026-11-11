package com.taca.cart.contracts;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.integrations.ProductHydrationClient;
import org.bson.Document;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "cart.indexes.initialize=true"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartBuyerHttpContractTest {
    private static final String BASE = "/api/v1/cart";
    private static final String BUYER = "buyer-contract-001";
    private static final String OTHER_BUYER = "buyer-contract-002";
    private static final String PRODUCT = "product-001";
    private static final String SKU = "sku-001";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7.0").withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_contract_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("product.base-url", () -> "");
        registry.add("inventory.base-url", () -> "");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

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

    @Test
    void shouldReturnStableEmptyCartContract() throws Exception {
        MvcResult result = mvc.perform(buyerGet(BUYER)
                        .header("X-Request-ID", "req-empty-cart"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-empty-cart"))
                .andReturn();

        JsonNode root = json(result);
        assertExactFields(root, "data", "meta");
        assertExactFields(root.path("data"),
                "cart_id", "items", "item_count", "updated_at");
        assertExactFields(root.path("meta"), "request_id");

        assertTrue(root.path("data").path("cart_id").isNull());
        assertTrue(root.path("data").path("items").isArray());
        assertEquals(0, root.path("data").path("items").size());
        assertEquals(0, root.path("data").path("item_count").asInt());
        assertTrue(root.path("data").path("updated_at").isNull());
        assertEquals("req-empty-cart",
                root.path("meta").path("request_id").asString());
        assertEquals(0L, cartCount());
        verifyNoInteractions(productCatalogClient, productHydrationClient,
                inventoryClient);
    }

    @Test
    void shouldReturn201WithStableAddResponseContract() throws Exception {
        stubSku();

        MvcResult result = mvc.perform(buyerPost(BUYER, 2)
                        .header("X-Request-ID", "req-add-contract"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-add-contract"))
                .andReturn();

        JsonNode root = json(result);
        assertExactFields(root, "data", "meta");
        assertExactFields(root.path("data"),
                "cart_id", "item_id", "quantity", "line_total");
        assertExactFields(root.path("meta"), "request_id");

        assertFalse(root.path("data").path("cart_id").asString().isBlank());
        assertFalse(root.path("data").path("item_id").asString().isBlank());
        assertEquals(2, root.path("data").path("quantity").asInt());
        assertEquals(498000L, root.path("data").path("line_total").asLong());
        assertEquals("req-add-contract",
                root.path("meta").path("request_id").asString());

        assertEquals(1L, cartCount());
        assertEquals(1L, rawCart(BUYER).getLong("version"));
        verify(productCatalogClient).validateSku(PRODUCT, SKU);
    }

    @Test
    void shouldReturnCartSnapshotWithoutChangingPersistedData()
            throws Exception {
        stubSku();
        MvcResult created = addItem(BUYER, 2, "req-create");
        String itemId = json(created).path("data")
                .path("item_id").asString();
        Document before = rawCart(BUYER);

        MvcResult result = mvc.perform(buyerGet(BUYER)
                        .header("X-Request-ID", "req-read"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-read"))
                .andReturn();

        JsonNode root = json(result);
        JsonNode data = root.path("data");

        assertExactFields(root, "data", "meta");
        assertExactFields(data,
                "cart_id", "items", "item_count", "updated_at");
        assertEquals(1, data.path("item_count").asInt());
        assertEquals(1, data.path("items").size());
        assertEquals(before.getString("_id"),
                data.path("cart_id").asString());
        assertNotNull(Instant.parse(data.path("updated_at").asString()));

        JsonNode item = data.path("items").get(0);
        assertExactFields(item,
                "item_id", "product_id", "sku_id", "shop_id",
                "title_snapshot", "sku_label_snapshot",
                "unit_price_snapshot", "quantity", "line_total",
                "available", "availability_status");

        assertEquals(itemId, item.path("item_id").asString());
        assertEquals(PRODUCT, item.path("product_id").asString());
        assertEquals(SKU, item.path("sku_id").asString());
        assertEquals(2, item.path("quantity").asInt());
        assertEquals(249000L, item.path("unit_price_snapshot").asLong());
        assertEquals(498000L, item.path("line_total").asLong());
        assertEquals("STALE", item.path("availability_status").asString());
        assertEquals("req-read",
                root.path("meta").path("request_id").asString());

        assertEquals(before, rawCart(BUYER));
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void shouldReturn200WithStableUpdateResponseContract()
            throws Exception {
        stubSku();
        String itemId = json(addItem(BUYER, 2, "req-create"))
                .path("data").path("item_id").asString();

        MvcResult result = mvc.perform(buyerPut(BUYER, itemId, 5)
                        .header("X-Request-ID", "req-update"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-update"))
                .andReturn();

        JsonNode root = json(result);
        assertExactFields(root, "data", "meta");
        assertExactFields(root.path("data"),
                "item_id", "quantity", "line_total");

        assertEquals(itemId, root.path("data").path("item_id").asString());
        assertEquals(5, root.path("data").path("quantity").asInt());
        assertEquals(1245000L,
                root.path("data").path("line_total").asLong());
        assertEquals("req-update",
                root.path("meta").path("request_id").asString());

        Document cart = rawCart(BUYER);
        assertEquals(2L, cart.getLong("version"));
        assertEquals(5, persistedQuantity(BUYER));
    }

    @Test
    void shouldReturn204WithNoBodyOnDelete() throws Exception {
        stubSku();
        String itemId = json(addItem(BUYER, 2, "req-create"))
                .path("data").path("item_id").asString();

        mvc.perform(buyerDelete(BUYER, itemId)
                        .header("X-Request-ID", "req-delete"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-delete"))
                .andExpect(content().string(""));

        Document cart = rawCart(BUYER);
        assertEquals(2L, cart.getLong("version"));
        assertTrue(cart.getList("items", Document.class).isEmpty());

        MvcResult repeated = mvc.perform(buyerDelete(BUYER, itemId))
                .andExpect(status().isNotFound())
                .andReturn();

        assertError(repeated, "CART_ITEM_NOT_FOUND");
    }

    @Test
    void shouldRejectInvalidQuantitiesWithErrorEnvelope()
            throws Exception {
        for (String quantity : new String[]{"0", "1000", "2.5", "null"}) {
            String body = """
                    {"product_id":"product-001",
                     "sku_id":"sku-001","quantity":%s}
                    """.formatted(quantity);

            MvcResult result = mvc.perform(authenticated(
                            post(BASE + "/items")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body), BUYER))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            assertError(result, "CART_INVALID_INPUT");
        }

        assertEquals(0L, cartCount());
        verifyNoInteractions(productCatalogClient);
    }

    @Test
    void shouldRejectInvalidIdempotencyHeaders() throws Exception {
        for (String key : new String[]{"invalid key", "x".repeat(129)}) {
            MvcResult result = mvc.perform(buyerPost(BUYER, 2)
                            .header("Idempotency-Key", key))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            assertError(result, "CART_INVALID_INPUT");
        }

        MvcResult duplicate = mvc.perform(buyerPost(BUYER, 2)
                        .header("Idempotency-Key", "key-1", "key-2"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertError(duplicate, "CART_INVALID_INPUT");
        assertEquals(0L, cartCount());
        assertEquals(0L, idempotencyCount());
        verifyNoInteractions(productCatalogClient);
    }

    @Test
    void shouldRejectMissingOrInvalidGatewayAuthentication()
            throws Exception {
        MvcResult missing = mvc.perform(get(BASE)
                        .header("X-User-ID", BUYER))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertError(missing, "CART_UNAUTHENTICATED");

        MvcResult invalidToken = mvc.perform(get(BASE)
                        .header("X-Gateway-Service-Token", "f".repeat(64))
                        .header("X-User-ID", BUYER))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertError(invalidToken, "CART_UNAUTHENTICATED");

        MvcResult missingBuyer = mvc.perform(get(BASE)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertError(missingBuyer, "CART_UNAUTHENTICATED");

        MvcResult duplicateBuyer = mvc.perform(get(BASE)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER, OTHER_BUYER))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertError(duplicateBuyer, "CART_UNAUTHENTICATED");

        assertEquals(0L, cartCount());
        verifyNoInteractions(productCatalogClient);
    }

    @Test
    void shouldPreserveBuyerIsolationForUpdateAndDelete()
            throws Exception {
        stubSku();
        String itemId = json(addItem(BUYER, 2, "req-create"))
                .path("data").path("item_id").asString();
        Document before = rawCart(BUYER);

        MvcResult update = mvc.perform(buyerPut(OTHER_BUYER, itemId, 5))
                .andExpect(status().isNotFound())
                .andReturn();
        assertError(update, "CART_ITEM_NOT_FOUND");

        MvcResult delete = mvc.perform(buyerDelete(OTHER_BUYER, itemId))
                .andExpect(status().isNotFound())
                .andReturn();
        assertError(delete, "CART_ITEM_NOT_FOUND");

        assertEquals(before, rawCart(BUYER));
        assertEquals(0L, mongoTemplate.getCollection("carts")
                .countDocuments(new Document("buyer_user_id", OTHER_BUYER)));
    }

    @Test
    void shouldReplayKeyedPostWithOriginalBodyAndNewHeader()
            throws Exception {
        stubSku();

        MvcResult first = mvc.perform(buyerPost(BUYER, 2)
                        .header("Idempotency-Key", "contract-key-001")
                        .header("X-Request-ID", "req-original"))
                .andExpect(status().isCreated())
                .andReturn();

        Document before = rawCart(BUYER);

        MvcResult replay = mvc.perform(buyerPost(BUYER, 2)
                        .header("Idempotency-Key", "contract-key-001")
                        .header("X-Request-ID", "req-retry"))
                .andExpect(status().isCreated())
                .andReturn();

        assertEquals(json(first), json(replay));
        assertEquals("req-original",
                json(replay).path("meta").path("request_id").asString());
        assertEquals("req-retry",
                replay.getResponse().getHeader("X-Request-ID"));
        assertEquals("no-store",
                replay.getResponse().getHeader("Cache-Control"));

        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, idempotencyCount());
        verify(productCatalogClient, times(1)).validateSku(PRODUCT, SKU);
    }

    @Test
    void shouldReturn409WhenIdempotencyKeyPayloadChanges()
            throws Exception {
        stubSku();

        mvc.perform(buyerPost(BUYER, 2)
                        .header("Idempotency-Key", "contract-key-002"))
                .andExpect(status().isCreated());

        Document before = rawCart(BUYER);

        MvcResult conflict = mvc.perform(buyerPost(BUYER, 3)
                        .header("Idempotency-Key", "contract-key-002"))
                .andExpect(status().isConflict())
                .andReturn();

        assertError(conflict, "CART_IDEMPOTENCY_CONFLICT");
        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, idempotencyCount());
        verify(productCatalogClient, times(1)).validateSku(PRODUCT, SKU);
    }

    @Test
    void shouldReturn503WhenProductDependencyIsUnavailable()
            throws Exception {
        when(productCatalogClient.validateSku(PRODUCT, SKU))
                .thenThrow(new CartException(
                        CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                        "Product Catalog is unavailable"));

        MvcResult result = mvc.perform(buyerPost(BUYER, 2))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertError(result, "CART_DEPENDENCY_UNAVAILABLE");
        assertEquals(0L, cartCount());
        assertEquals(0L, idempotencyCount());
    }

    @Test
    void shouldGenerateValidRequestIdWhenIncomingHeaderIsInvalid()
            throws Exception {
        MvcResult result = mvc.perform(buyerGet(BUYER)
                        .header("X-Request-ID", "invalid request id"))
                .andExpect(status().isOk())
                .andReturn();

        String requestId = result.getResponse().getHeader("X-Request-ID");
        assertNotNull(requestId);
        assertTrue(requestId.matches("[A-Za-z0-9._:-]{1,64}"));
        assertNotEquals("invalid request id", requestId);
        assertEquals(requestId,
                json(result).path("meta").path("request_id").asString());
    }

    private void stubSku() {
        when(productCatalogClient.validateSku(PRODUCT, SKU))
                .thenReturn(new ValidatedSkuSnapshot(
                        PRODUCT, SKU, "shop-001",
                        "Cotton jacket", "Black / L", 249000L));
    }

    private MvcResult addItem(String buyer, int quantity, String requestId)
            throws Exception {
        return mvc.perform(buyerPost(buyer, quantity)
                        .header("X-Request-ID", requestId))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MockHttpServletRequestBuilder buyerGet(String buyer) {
        return authenticated(get(BASE), buyer);
    }

    private MockHttpServletRequestBuilder buyerPost(
            String buyer, int quantity) {
        return authenticated(post(BASE + "/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"product_id":"product-001",
                         "sku_id":"sku-001","quantity":%d}
                        """.formatted(quantity)), buyer);
    }

    private MockHttpServletRequestBuilder buyerPut(
            String buyer, String itemId, int quantity) {
        return authenticated(put(BASE + "/items/" + itemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":%d}".formatted(quantity)), buyer);
    }

    private MockHttpServletRequestBuilder buyerDelete(
            String buyer, String itemId) {
        return authenticated(delete(BASE + "/items/" + itemId), buyer);
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder builder, String buyer) {
        return builder
                .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                .header("X-User-ID", buyer);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private void assertExactFields(JsonNode node, String... fields) {
        assertTrue(node.isObject(), "Expected JSON object");
        assertEquals(fields.length, node.size(), "Unexpected JSON fields");
        for (String field : fields) {
            assertTrue(node.has(field), "Missing JSON field: " + field);
        }
    }

    private void assertError(MvcResult result, String code)
            throws Exception {
        JsonNode root = json(result);
        assertExactFields(root, "error");

        JsonNode error = root.path("error");
        assertExactFields(error, "code", "message", "details", "trace_id");

        assertEquals(code, error.path("code").asString());
        assertFalse(error.path("message").asString().isBlank());
        assertTrue(error.path("details").isObject());
    }

    private Document rawCart(String buyer) {
        Document cart = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer)).first();
        assertNotNull(cart);
        return cart;
    }

    private int persistedQuantity(String buyer) {
        return rawCart(buyer).getList("items", Document.class)
                .getFirst().getInteger("quantity");
    }

    private long cartCount() {
        return mongoTemplate.getCollection("carts").countDocuments();
    }

    private long idempotencyCount() {
        return mongoTemplate.getCollection("cart_idempotency_keys")
                .countDocuments();
    }
}

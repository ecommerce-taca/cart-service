package com.taca.cart.api;

import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartRequestHasher;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "cart.indexes.initialize=false",
                "cart.write-retry-max=6"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartIdempotencyHttpRegressionTest {
    private static final String PATH = "/api/v1/cart/items";
    private static final String BUYER = "buyer-001";
    private static final String PRODUCT = "product-001";
    private static final String SKU = "sku-001";
    private static final String KEY = "5g-add-item-001";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CartPersistenceMapper mapper;

    @Autowired
    private CartRequestHasher hasher;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @BeforeEach
    void setUp() {
        if (!mongoTemplate.collectionExists("carts")) {
            mongoTemplate.createCollection("carts");
        }
        if (!mongoTemplate.collectionExists("cart_idempotency_keys")) {
            mongoTemplate.createCollection("cart_idempotency_keys");
        }

        mongoTemplate.indexOps("carts").createIndex(
                new Index().on("buyer_user_id", Sort.Direction.ASC)
                        .unique().named("ux_carts_buyer_user_id"));

        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys").deleteMany(new Document());
    }

    @Test
    void shouldCreateCartAndIdempotencyRecordTogether() throws Exception {
        stubSku(SKU);

        MvcResult result = send(BUYER, KEY, "req-first", body(SKU, 2));

        assertStatus(result, 201);
        assertEquals(2, json(result).path("data").path("quantity").asInt());
        assertEquals(498000L, json(result).path("data").path("line_total").asLong());
        assertEquals("req-first", json(result).path("meta").path("request_id").asString());

        Document cart = rawCart(BUYER);
        Document key = rawKey(BUYER, KEY);
        Document item = cart.getList("items", Document.class).getFirst();

        assertEquals(1L, countCarts());
        assertEquals(1L, countKeys());
        assertEquals(1L, cart.getLong("version"));
        assertEquals(1L, item.getLong("item_revision"));
        assertEquals(2, item.getInteger("quantity"));
        assertEquals(201, key.getInteger("response_status"));
        assertEquals(hasher.hash(request(SKU, 2)), key.getString("request_hash"));
        assertTrue(key.getDate("expires_at").after(key.getDate("created_at")));
        assertEquals(cart.getString("_id"),
                json(result).path("data").path("cart_id").asString());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
    }

    @Test
    void shouldReplayWhenProductCatalogBecomesUnavailable() throws Exception {
        stubSku(SKU);

        MvcResult first = send(BUYER, KEY, "req-original", body(SKU, 2));
        assertStatus(first, 201);
        Document before = rawCart(BUYER);

        when(productCatalogClient.validateSku(PRODUCT, SKU))
                .thenThrow(new CartException(
                        CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                        "Product Catalog is unavailable"));

        MvcResult replay = send(BUYER, KEY, "req-retry", body(SKU, 2));

        assertStatus(replay, 201);
        assertEquals(json(first), json(replay));
        assertEquals("req-original",
                json(replay).path("meta").path("request_id").asString());
        assertEquals("req-retry", replay.getResponse().getHeader("X-Request-ID"));
        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, countKeys());

        verify(productCatalogClient, times(1)).validateSku(PRODUCT, SKU);
    }

    @Test
    void shouldReplayEquivalentJsonWithDifferentFieldOrder() throws Exception {
        stubSku(SKU);

        MvcResult first = send(BUYER, KEY, "req-original",
                "{\"product_id\":\"product-001\",\"sku_id\":\"sku-001\",\"quantity\":2}");

        MvcResult replay = send(BUYER, KEY, "req-retry", """
                {
                  "quantity": 2,
                  "sku_id": "sku-001",
                  "product_id": "product-001"
                }
                """);

        assertStatus(first, 201);
        assertStatus(replay, 201);
        assertEquals(json(first), json(replay));
        assertEquals(1L, rawCart(BUYER).getLong("version"));
        assertEquals(1L, countKeys());

        verify(productCatalogClient, times(1)).validateSku(PRODUCT, SKU);
    }

    @Test
    void shouldRejectDifferentBodyWithSameKey() throws Exception {
        stubSku(SKU);

        assertStatus(send(BUYER, KEY, "req-first", body(SKU, 2)), 201);

        Document beforeCart = rawCart(BUYER);
        Document beforeKey = rawKey(BUYER, KEY);

        MvcResult conflict = send(BUYER, KEY, "req-conflict", body(SKU, 3));

        assertStatus(conflict, 409);
        assertEquals("CART_IDEMPOTENCY_CONFLICT",
                json(conflict).path("error").path("code").asString());
        assertEquals(beforeCart, rawCart(BUYER));
        assertEquals(beforeKey, rawKey(BUYER, KEY));

        verify(productCatalogClient, times(1)).validateSku(PRODUCT, SKU);
    }

    @Test
    void shouldCommitIndependentMutationsForDifferentKeys() throws Exception {
        stubSku(SKU);

        MvcResult first = send(BUYER, "key-001", "req-001", body(SKU, 2));
        MvcResult second = send(BUYER, "key-002", "req-002", body(SKU, 3));

        assertStatus(first, 201);
        assertStatus(second, 201);
        assertEquals(2, json(first).path("data").path("quantity").asInt());
        assertEquals(5, json(second).path("data").path("quantity").asInt());
        assertEquals(5, quantity(BUYER));
        assertEquals(2L, rawCart(BUYER).getLong("version"));
        assertEquals(2L, rawCart(BUYER).getList("items", Document.class)
                .getFirst().getLong("item_revision"));
        assertEquals(1L, countCarts());
        assertEquals(2L, countKeys());
    }

    @Test
    void shouldScopeIdenticalKeyByBuyer() throws Exception {
        stubSku(SKU);

        MvcResult first = send("buyer-A", KEY, "req-a", body(SKU, 2));
        MvcResult second = send("buyer-B", KEY, "req-b", body(SKU, 3));

        assertStatus(first, 201);
        assertStatus(second, 201);
        assertEquals(2, quantity("buyer-A"));
        assertEquals(3, quantity("buyer-B"));
        assertEquals(2L, countCarts());
        assertEquals(2L, countKeys());
        assertNotEquals(rawCart("buyer-A").getString("_id"),
                rawCart("buyer-B").getString("_id"));
    }


    @Test
    void shouldReuseExpiredKeyEvenWhenTtlHasNotDeletedRecord() throws Exception {
        stubSku(SKU);

        assertStatus(send(BUYER, KEY, "req-old", body(SKU, 2)), 201);

        Instant expiredCreatedAt = Instant.now().minusSeconds(86410);
        Instant expiredExpiresAt = expiredCreatedAt.plusSeconds(86400);

        mongoTemplate.getCollection("cart_idempotency_keys").updateOne(
                new Document("_id", keyId(BUYER, KEY)),
                new Document("$set", new Document()
                        .append("created_at", Date.from(expiredCreatedAt))
                        .append("expires_at", Date.from(expiredExpiresAt))));

        assertEquals(1L, countKeys());

        MvcResult result = send(BUYER, KEY, "req-new", body(SKU, 3));

        assertStatus(result, 201);
        assertEquals(5, json(result).path("data").path("quantity").asInt());
        assertEquals("req-new",
                json(result).path("meta").path("request_id").asString());
        assertEquals(5, quantity(BUYER));
        assertEquals(2L, rawCart(BUYER).getLong("version"));
        assertEquals(1L, countKeys());

        Document stored = rawKey(BUYER, KEY);
        assertEquals(hasher.hash(request(SKU, 3)), stored.getString("request_hash"));
        assertTrue(stored.getDate("expires_at").toInstant().isAfter(Instant.now()));
    }

    @Test
    void shouldKeepNonKeyedPostBehaviorUnchanged() throws Exception {
        stubSku(SKU);

        MvcResult first = send(BUYER, null, "req-001", body(SKU, 2));
        MvcResult second = send(BUYER, null, "req-002", body(SKU, 3));

        assertStatus(first, 201);
        assertStatus(second, 201);
        assertEquals(5, quantity(BUYER));
        assertEquals(2L, rawCart(BUYER).getLong("version"));
        assertEquals(0L, countKeys());
    }

    @Test
    void shouldRejectInvalidRequestsWithoutWriting() throws Exception {
        assertStatus(send(BUYER, "invalid key", "req-invalid", body(SKU, 2)), 400);
        assertStatus(send(BUYER, "x".repeat(129), "req-long", body(SKU, 2)), 400);
        assertStatus(send(BUYER, null, "req-quantity", body(SKU, 0)), 400);

        MvcResult duplicate = mvc.perform(post(PATH).with(gateway(BUYER))
                        .header("Idempotency-Key", "key-1", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKU, 2)))
                .andReturn();

        assertStatus(duplicate, 400);

        MvcResult unauthenticated = mvc.perform(post(PATH)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKU, 2)))
                .andReturn();

        assertStatus(unauthenticated, 401);
        assertEquals(0L, countCarts());
        assertEquals(0L, countKeys());
        verifyNoInteractions(productCatalogClient);
    }

    @Test
    void shouldNotCreateCartOrKeyWhenProductUnavailable() throws Exception {
        when(productCatalogClient.validateSku(PRODUCT, SKU))
                .thenThrow(new CartException(
                        CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                        "Product or SKU is unavailable"));

        MvcResult result = send(BUYER, KEY, "req-product", body(SKU, 2));

        assertStatus(result, 409);
        assertEquals("CART_PRODUCT_UNAVAILABLE",
                json(result).path("error").path("code").asString());
        assertEquals(0L, countCarts());
        assertEquals(0L, countKeys());
    }

    @Test
    void shouldStoreKeyEvenWhenCartQuantityIsAlready999() throws Exception {
        stubSku(SKU);

        assertStatus(send(BUYER, null, "req-seed", body(SKU, 999)), 201);
        Document before = rawCart(BUYER);

        MvcResult keyed = send(BUYER, KEY, "req-capped", body(SKU, 1));

        assertStatus(keyed, 201);
        assertEquals(999, json(keyed).path("data").path("quantity").asInt());
        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, countKeys());

        MvcResult replay = send(BUYER, KEY, "req-replay", body(SKU, 1));

        assertStatus(replay, 201);
        assertEquals(json(keyed), json(replay));
        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldNotCommitKeyWhenCartHas100Items() throws Exception {
        List<CartItem> items = IntStream.range(0, 100)
                .mapToObj(i -> new CartItem(
                        "item-" + i, PRODUCT, "sku-" + i, "shop-001",
                        1, 1L, "Product", "Default", 1000L,
                        true, NOW, NOW))
                .toList();

        mongoTemplate.insert(mapper.toDocument(
                new Cart("cart-full", BUYER, items, 1L, NOW, NOW)));

        stubSku("sku-new");
        Document before = rawCart(BUYER);

        MvcResult result = send(BUYER, KEY, "req-full", body("sku-new", 1));

        assertStatus(result, 409);
        assertEquals("CART_TOO_MANY_ITEMS",
                json(result).path("error").path("code").asString());
        assertEquals(before, rawCart(BUYER));
        assertEquals(0L, countKeys());
    }

    @Test
    void shouldNotRestoreDeletedItemWhenOriginalPostIsReplayed() throws Exception {
        stubSku(SKU);

        MvcResult first = send(BUYER, KEY, "req-original", body(SKU, 2));
        assertStatus(first, 201);

        String itemId = json(first).path("data").path("item_id").asString();

        mvc.perform(delete("/api/v1/cart/items/" + itemId)
                        .with(gateway(BUYER)))
                .andExpect(status().isNoContent());

        Document beforeReplay = rawCart(BUYER);
        assertTrue(beforeReplay.getList("items", Document.class).isEmpty());

        MvcResult replay = send(BUYER, KEY, "req-retry", body(SKU, 2));

        assertStatus(replay, 201);
        assertEquals(json(first), json(replay));
        assertEquals(beforeReplay, rawCart(BUYER));
        assertEquals(2L, rawCart(BUYER).getLong("version"));
        assertEquals(1L, countKeys());

        verify(productCatalogClient, times(1)).validateSku(PRODUCT, SKU);
    }

    @Test
    void shouldCommitOnlyOneMutationForConcurrentHttpRequests() throws Exception {
        stubSku(SKU);
        assertStatus(send(BUYER, null, "req-seed", body(SKU, 1)), 201);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<MvcResult> firstTask = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("First request timed out");
            }
            return send(BUYER, KEY, "req-a", body(SKU, 2));
        };

        Callable<MvcResult> secondTask = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Second request timed out");
            }
            return send(BUYER, KEY, "req-b", body(SKU, 2));
        };

        try {
            Future<MvcResult> first = executor.submit(firstTask);
            Future<MvcResult> second = executor.submit(secondTask);

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            MvcResult resultA = first.get(30, TimeUnit.SECONDS);
            MvcResult resultB = second.get(30, TimeUnit.SECONDS);

            assertStatus(resultA, 201);
            assertStatus(resultB, 201);
            assertEquals(json(resultA), json(resultB));
            assertEquals(3, quantity(BUYER));
            assertEquals(2L, rawCart(BUYER).getLong("version"));
            assertEquals(1L, countKeys());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void stubSku(String skuId) {
        when(productCatalogClient.validateSku(PRODUCT, skuId))
                .thenReturn(new ValidatedSkuSnapshot(
                        PRODUCT, skuId, "shop-001",
                        "Cotton jacket", "Black / L", 249000L));
    }

    private AddCartItemRequest request(String skuId, int quantity) {
        return new AddCartItemRequest(PRODUCT, skuId, quantity);
    }

    private String body(String skuId, int quantity) {
        return """
                {"product_id":"%s","sku_id":"%s","quantity":%d}
                """.formatted(PRODUCT, skuId, quantity);
    }

    private MvcResult send(String buyer, String key, String requestId,
                           String payload) throws Exception {
        var builder = post(PATH).with(gateway(buyer))
                .header("X-Request-ID", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);

        if (key != null) builder.header("Idempotency-Key", key);
        return mvc.perform(builder).andReturn();
    }

    private RequestPostProcessor gateway(String buyer) {
        return request -> {
            request.addHeader("X-Gateway-Service-Token", GATEWAY_TOKEN);
            request.addHeader("X-User-ID", buyer);
            return request;
        };
    }

    private JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private void assertStatus(MvcResult result, int expected) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private Document rawCart(String buyer) {
        Document cart = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer)).first();
        assertNotNull(cart);
        return cart;
    }

    private Document keyId(String buyer, String key) {
        return new Document("buyer_user_id", buyer).append("key", key);
    }

    private Document rawKey(String buyer, String key) {
        Document record = mongoTemplate.getCollection("cart_idempotency_keys")
                .find(new Document("_id", keyId(buyer, key))).first();
        assertNotNull(record);
        return record;
    }

    private int quantity(String buyer) {
        return rawCart(buyer).getList("items", Document.class)
                .getFirst().getInteger("quantity");
    }

    private long countCarts() {
        return mongoTemplate.getCollection("carts").countDocuments();
    }

    private long countKeys() {
        return mongoTemplate.getCollection("cart_idempotency_keys").countDocuments();
    }
}

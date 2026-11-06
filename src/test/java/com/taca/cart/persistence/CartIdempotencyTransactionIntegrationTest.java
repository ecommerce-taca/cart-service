package com.taca.cart.persistence;

import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartIdempotencyTransactionService;
import com.taca.cart.application.CartRequestHasher;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartIdempotencyId;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import com.taca.cart.persistence.repository.CartIdempotencyMongoRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cart.indexes.initialize=false"
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartIdempotencyTransactionIntegrationTest {
    private static final String BUYER = "buyer-001";
    private static final String KEY = "add-item-001";
    private static final String PRODUCT = "product-001";
    private static final String SKU = "sku-001";
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
    }

    @Autowired
    private CartIdempotencyTransactionService transactionService;

    @Autowired
    private CartIdempotencyMongoRepository repository;

    @Autowired
    private CartPersistenceMapper mapper;

    @Autowired
    private CartRequestHasher hasher;

    @Autowired
    private MongoTemplate mongoTemplate;

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
    void shouldCommitCartAndKeyAtomically() {
        CartIdempotencyKey result = execute(BUYER, KEY, 2, "req-001");

        assertEquals(201, result.responseStatus());
        assertEquals(hasher.hash(request(2)), result.requestHash());
        assertEquals(1L, countCarts());
        assertEquals(1L, repository.count());

        Document cart = rawCart(BUYER);
        Document key = rawKey(BUYER, KEY);
        Document item = cart.getList("items", Document.class).getFirst();

        assertEquals(1L, cart.getLong("version"));
        assertEquals(2, item.getInteger("quantity"));
        assertEquals(1L, item.getLong("item_revision"));
        assertEquals(249000L, item.getLong("unit_price_snapshot"));

        assertEquals(201, key.getInteger("response_status"));
        assertEquals(result.requestHash(), key.getString("request_hash"));

        Document response = key.get("response", Document.class);
        Document data = response.get("data", Document.class);
        Document meta = response.get("meta", Document.class);

        assertEquals(cart.getString("_id"), data.getString("cart_id"));
        assertEquals(item.getString("item_id"), data.getString("item_id"));
        assertEquals(2, data.getInteger("quantity"));
        assertEquals(498000L, ((Number) data.get("line_total")).longValue());
        assertEquals("req-001", meta.getString("request_id"));
    }

    @Test
    void shouldReplayOriginalResponseWithoutMutation() {
        CartIdempotencyKey first = execute(BUYER, KEY, 2, "req-original");
        Document beforeCart = rawCart(BUYER);
        Document beforeKey = rawKey(BUYER, KEY);

        CartIdempotencyKey replay = execute(BUYER, KEY, 2, "req-retry");

        assertEquals(first.response(), replay.response());
        assertEquals(first.responseStatus(), replay.responseStatus());
        assertEquals("req-original",
                ((Map<?, ?>) replay.response().get("meta")).get("request_id"));

        assertEquals(beforeCart, rawCart(BUYER));
        assertEquals(beforeKey, rawKey(BUYER, KEY));
        assertEquals(1L, repository.count());
    }

    @Test
    void shouldRejectSameKeyWithDifferentRequest() {
        execute(BUYER, KEY, 2, "req-original");

        Document beforeCart = rawCart(BUYER);
        Document beforeKey = rawKey(BUYER, KEY);

        CartException ex = assertThrows(CartException.class,
                () -> execute(BUYER, KEY, 3, "req-different"));

        assertEquals(CartErrorCode.CART_IDEMPOTENCY_CONFLICT, ex.getCode());
        assertEquals(beforeCart, rawCart(BUYER));
        assertEquals(beforeKey, rawKey(BUYER, KEY));
    }

    @Test
    void shouldReplaceExpiredKeyWithoutWaitingForTtl() {
        Instant createdAt = Instant.now().minusSeconds(86402);
        Instant expiresAt = Instant.now().minusSeconds(2);

        saveKey(BUYER, KEY, hasher.hash(request(1)), createdAt, expiresAt);

        CartIdempotencyKey result = execute(BUYER, KEY, 2, "req-new");

        assertEquals(hasher.hash(request(2)), result.requestHash());
        assertEquals(201, result.responseStatus());
        assertEquals(1L, countCarts());
        assertEquals(1L, repository.count());

        Document stored = rawKey(BUYER, KEY);
        assertEquals(result.requestHash(), stored.getString("request_hash"));
        assertTrue(stored.getDate("expires_at").toInstant().isAfter(Instant.now()));
        assertEquals("req-new",
                stored.get("response", Document.class)
                        .get("meta", Document.class)
                        .getString("request_id"));
    }

    @Test
    void shouldScopeSameKeyByBuyer() {
        CartIdempotencyKey first = execute("buyer-001", KEY, 2, "req-a");
        CartIdempotencyKey second = execute("buyer-002", KEY, 3, "req-b");

        assertEquals(2L, countCarts());
        assertEquals(2L, repository.count());

        assertEquals(2, ((Map<?, ?>) first.response().get("data")).get("quantity"));
        assertEquals(3, ((Map<?, ?>) second.response().get("data")).get("quantity"));

        assertNotEquals(rawCart("buyer-001").getString("_id"),
                rawCart("buyer-002").getString("_id"));
    }

    @Test
    void shouldCommitDifferentKeysForSameBuyer() {
        CartIdempotencyKey first = execute(BUYER, "key-001", 2, "req-001");
        CartIdempotencyKey second = execute(BUYER, "key-002", 3, "req-002");

        assertEquals(2, ((Map<?, ?>) first.response().get("data")).get("quantity"));
        assertEquals(5, ((Map<?, ?>) second.response().get("data")).get("quantity"));

        assertEquals(1L, countCarts());
        assertEquals(2L, repository.count());

        Document cart = rawCart(BUYER);
        assertEquals(2L, cart.getLong("version"));

        Document item = cart.getList("items", Document.class).getFirst();
        assertEquals(5, item.getInteger("quantity"));
        assertEquals(2L, item.getLong("item_revision"));
    }

    @Test
    void shouldStoreKeyWhenCartIsAlreadyCappedAt999() {
        CartItem item = new CartItem(
                "item-001", PRODUCT, SKU, "shop-001",
                999, 1L, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW);

        mongoTemplate.insert(mapper.toDocument(
                new Cart("cart-001", BUYER, List.of(item), 1L, NOW, NOW)));

        Document before = rawCart(BUYER);
        CartIdempotencyKey result = execute(BUYER, KEY, 1, "req-capped");

        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, repository.count());

        Map<?, ?> data = (Map<?, ?>) result.response().get("data");
        assertEquals(999, data.get("quantity"));
        assertEquals(248751000L, data.get("line_total"));

        CartIdempotencyKey replay = execute(BUYER, KEY, 1, "req-retry");
        assertEquals(result.response(), replay.response());
        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldRollbackCartWhenIdempotencyInsertFails() {
        String hash = hasher.hash(request(2));

        mongoTemplate.indexOps("cart_idempotency_keys").createIndex(
                new Index().on("request_hash", Sort.Direction.ASC)
                        .unique().named("ux_test_request_hash"));

        try {
            saveKey("buyer-other", "existing-key", hash,
                    Instant.now(), Instant.now().plusSeconds(86400));

            CartException ex = assertThrows(CartException.class,
                    () -> execute(BUYER, KEY, 2, "req-failure"));

            assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
            assertEquals(0L, countCarts());
            assertEquals(1L, repository.count());

            Document id = new Document("buyer_user_id", BUYER).append("key", KEY);
            assertNull(mongoTemplate.getCollection("cart_idempotency_keys")
                    .find(new Document("_id", id)).first());
        } finally {
            mongoTemplate.indexOps("cart_idempotency_keys")
                    .dropIndex("ux_test_request_hash");
        }
    }

    @Test
    void shouldNotCommitKeyWhenCartMutationFails() {
        List<CartItem> items = IntStream.range(0, 100)
                .mapToObj(i -> new CartItem(
                        "item-" + i, PRODUCT, "sku-" + i, "shop-001",
                        1, 1L, "Product", "Default", 1000L,
                        true, NOW, NOW))
                .toList();

        mongoTemplate.insert(mapper.toDocument(
                new Cart("cart-full", BUYER, items, 1L, NOW, NOW)));

        CartException ex = assertThrows(CartException.class,
                () -> execute(BUYER, KEY, 1, "req-full"));

        assertEquals(CartErrorCode.CART_TOO_MANY_ITEMS, ex.getCode());
        assertEquals(100, rawCart(BUYER).getList("items", Document.class).size());
        assertEquals(1L, rawCart(BUYER).getLong("version"));
        assertEquals(0L, repository.count());
    }

    private CartIdempotencyKey execute(
            String buyer, String key, int quantity, String requestId) {
        return transactionService.execute(
                buyer, key, request(quantity), sku(), requestId);
    }

    private AddCartItemRequest request(int quantity) {
        return new AddCartItemRequest(PRODUCT, SKU, quantity);
    }

    private ValidatedSkuSnapshot sku() {
        return new ValidatedSkuSnapshot(
                PRODUCT, SKU, "shop-001",
                "Cotton jacket", "Black / L", 249000L);
    }

    private void saveKey(String buyer, String key, String hash,
                         Instant createdAt, Instant expiresAt) {
        Map<String, Object> response = Map.of(
                "data", Map.of(
                        "cart_id", "cart-old",
                        "item_id", "item-old",
                        "quantity", 1,
                        "line_total", 249000L),
                "meta", Map.of("request_id", "req-old"));

        CartIdempotencyKey record = new CartIdempotencyKey(
                new CartIdempotencyId(buyer, key),
                hash, response, 201, expiresAt, createdAt);

        repository.insert(mapper.toDocument(record));
    }

    private Document rawCart(String buyer) {
        Document result = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer)).first();

        assertNotNull(result);
        return result;
    }

    private Document rawKey(String buyer, String key) {
        Document id = new Document("buyer_user_id", buyer).append("key", key);
        Document result = mongoTemplate.getCollection("cart_idempotency_keys")
                .find(new Document("_id", id)).first();

        assertNotNull(result);
        return result;
    }

    private long countCarts() {
        return mongoTemplate.getCollection("carts").countDocuments();
    }
}

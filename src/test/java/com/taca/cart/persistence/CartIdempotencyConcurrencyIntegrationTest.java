package com.taca.cart.persistence;

import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartIdempotencyTransactionService;
import com.taca.cart.application.CartMutationService;
import com.taca.cart.application.CartRequestHasher;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.CartIdempotencyId;
import com.taca.cart.domain.CartIdempotencyKey;
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
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "cart.indexes.initialize=false",
                "cart.write-retry-max=6"
        }
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartIdempotencyConcurrencyIntegrationTest {
    private static final String BUYER = "buyer-001";
    private static final String PRODUCT = "product-001";
    private static final String SKU = "sku-001";
    private static final String KEY = "add-item-001";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
    }

    @Autowired
    private CartIdempotencyTransactionService service;

    @Autowired
    private CartMutationService mutationService;

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
    void shouldCommitOnlyOneMutationForSameKeyAndBody() throws Exception {
        mutationService.addItem(BUYER, sku(), 1);

        List<CartIdempotencyKey> results = inParallel(
                () -> execute(BUYER, KEY, 2, "req-a"),
                () -> execute(BUYER, KEY, 2, "req-b"));

        assertEquals(results.get(0).response(), results.get(1).response());
        assertEquals(201, results.get(0).responseStatus());
        assertEquals(201, results.get(1).responseStatus());

        assertEquals(3, quantity(BUYER));
        assertEquals(2L, version(BUYER));
        assertEquals(1L, repository.count());
    }

    @Test
    void shouldCommitBothMutationsForDifferentKeys() throws Exception {
        mutationService.addItem(BUYER, sku(), 1);

        List<CartIdempotencyKey> results = inParallel(
                () -> execute(BUYER, "key-a", 2, "req-a"),
                () -> execute(BUYER, "key-b", 3, "req-b"));

        assertEquals(201, results.get(0).responseStatus());
        assertEquals(201, results.get(1).responseStatus());

        assertEquals(6, quantity(BUYER));
        assertEquals(3L, version(BUYER));
        assertEquals(2L, repository.count());
    }

    @Test
    void shouldRejectOneConcurrentRequestWhenBodiesDiffer() throws Exception {
        mutationService.addItem(BUYER, sku(), 1);

        List<Object> results = inParallel(
                () -> executeOrCapture(BUYER, KEY, 2, "req-a"),
                () -> executeOrCapture(BUYER, KEY, 3, "req-b"));

        long committed = results.stream()
                .filter(CartIdempotencyKey.class::isInstance).count();
        long conflicts = results.stream()
                .filter(value -> value instanceof CartException ex
                        && ex.getCode() == CartErrorCode.CART_IDEMPOTENCY_CONFLICT)
                .count();

        assertEquals(1L, committed);
        assertEquals(1L, conflicts);
        assertEquals(1L, repository.count());

        CartIdempotencyKey winner = results.stream()
                .filter(CartIdempotencyKey.class::isInstance)
                .map(CartIdempotencyKey.class::cast)
                .findFirst().orElseThrow();

        int committedQuantity = ((Number)
                ((Map<?, ?>) winner.response().get("data")).get("quantity")).intValue();

        assertEquals(committedQuantity, quantity(BUYER));
        assertEquals(2L, version(BUYER));
    }

    @Test
    void shouldReclaimExpiredKeyOnceUnderConcurrency() throws Exception {
        mutationService.addItem(BUYER, sku(), 1);
        saveExpiredKey(BUYER, KEY);

        List<CartIdempotencyKey> results = inParallel(
                () -> execute(BUYER, KEY, 2, "req-a"),
                () -> execute(BUYER, KEY, 2, "req-b"));

        assertEquals(results.get(0).response(), results.get(1).response());
        assertEquals(3, quantity(BUYER));
        assertEquals(2L, version(BUYER));
        assertEquals(1L, repository.count());

        Document storedKey = rawKey(BUYER, KEY);
        assertEquals(hasher.hash(request(2)), storedKey.getString("request_hash"));
        assertTrue(storedKey.getDate("expires_at").toInstant().isAfter(Instant.now()));
    }

    @Test
    void shouldAllowSameKeyForDifferentBuyers() throws Exception {
        List<CartIdempotencyKey> results = inParallel(
                () -> execute("buyer-001", KEY, 2, "req-a"),
                () -> execute("buyer-002", KEY, 3, "req-b"));

        assertEquals(201, results.get(0).responseStatus());
        assertEquals(201, results.get(1).responseStatus());

        assertEquals(2, quantity("buyer-001"));
        assertEquals(3, quantity("buyer-002"));
        assertEquals(2L, repository.count());
        assertEquals(2L, mongoTemplate.getCollection("carts").countDocuments());
    }

    private CartIdempotencyKey execute(
            String buyer, String key, int quantity, String requestId) {
        return service.execute(buyer, key, request(quantity), sku(), requestId);
    }

    private Object executeOrCapture(
            String buyer, String key, int quantity, String requestId) {
        try {
            return execute(buyer, key, quantity, requestId);
        } catch (CartException ex) {
            return ex;
        }
    }

    private AddCartItemRequest request(int quantity) {
        return new AddCartItemRequest(PRODUCT, SKU, quantity);
    }

    private ValidatedSkuSnapshot sku() {
        return new ValidatedSkuSnapshot(
                PRODUCT, SKU, "shop-001",
                "Cotton jacket", "Black / L", 249000L);
    }

    private void saveExpiredKey(String buyer, String key) {
        Instant createdAt = Instant.now().minusSeconds(172800);
        Instant expiresAt = Instant.now().minusSeconds(86400);

        CartIdempotencyKey record = new CartIdempotencyKey(
                new CartIdempotencyId(buyer, key),
                hasher.hash(request(1)),
                Map.of(
                        "data", Map.of(
                                "cart_id", "old-cart",
                                "item_id", "old-item",
                                "quantity", 1,
                                "line_total", 249000L),
                        "meta", Map.of("request_id", "req-old")),
                201, expiresAt, createdAt);

        repository.insert(mapper.toDocument(record));
    }

    private Document rawCart(String buyer) {
        Document cart = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer)).first();
        assertNotNull(cart);
        return cart;
    }

    private Document rawKey(String buyer, String key) {
        Document id = new Document("buyer_user_id", buyer).append("key", key);
        Document record = mongoTemplate.getCollection("cart_idempotency_keys")
                .find(new Document("_id", id)).first();
        assertNotNull(record);
        return record;
    }

    private int quantity(String buyer) {
        return rawCart(buyer).getList("items", Document.class)
                .getFirst().getInteger("quantity");
    }

    private long version(String buyer) {
        return rawCart(buyer).getLong("version");
    }

    private <T> List<T> inParallel(
            Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<T> firstFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("First worker timed out");
                }
                return first.call();
            });

            Future<T> secondFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Second worker timed out");
                }
                return second.call();
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            T firstResult = firstFuture.get(30, TimeUnit.SECONDS);
            T secondResult = secondFuture.get(30, TimeUnit.SECONDS);

            return List.of(firstResult, secondResult);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
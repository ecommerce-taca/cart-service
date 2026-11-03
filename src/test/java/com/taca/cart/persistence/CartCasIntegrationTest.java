
package com.taca.cart.persistence;

import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cart.indexes.initialize=true"
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartCasIntegrationTest {
    private static final String BUYER = "buyer-001";
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CartCasPersistence persistence;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
    }

    @Test
    void shouldCreateCartAndIncrementVersionOnUpdate() {
        Cart created = persistence.mutate(BUYER,
                snapshot -> new Cart("cart-001", BUYER, List.of(), 0L, NOW, NOW));

        assertEquals(1L, created.version());

        Cart updated = persistence.mutate(BUYER,
                snapshot -> append(snapshot.orElseThrow(), item("item-001")));

        assertEquals(2L, updated.version());
        assertEquals(1, updated.items().size());

        Cart persisted = persistence.findByBuyerUserId(BUYER).orElseThrow();

        assertEquals(2L, persisted.version());
        assertEquals("item-001", persisted.items().getFirst().itemId());

        Document raw = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", BUYER))
                .first();

        assertNotNull(raw);
        assertEquals(2L, raw.getLong("version"));
        assertEquals(1, raw.getList("items", Document.class).size());
    }

    @Test
    void shouldNotIncrementVersionOnNoOp() {
        Cart created = persistence.mutate(BUYER,
                snapshot -> new Cart("cart-001", BUYER, List.of(item("item-001")), 0L, NOW, NOW));

        Cart unchanged = persistence.mutate(BUYER, snapshot -> {
            Cart current = snapshot.orElseThrow();
            return new Cart(
                    current.id(), current.buyerUserId(), current.items(),
                    current.version(), current.createdAt(), current.updatedAt().plusSeconds(60)
            );
        });

        assertEquals(created.version(), unchanged.version());
        assertEquals(created.updatedAt(), unchanged.updatedAt());

        Cart persisted = persistence.findByBuyerUserId(BUYER).orElseThrow();

        assertEquals(1L, persisted.version());
        assertEquals(created.updatedAt(), persisted.updatedAt());
    }

    @Test
    void shouldMergeConcurrentUpdatesWithoutLostUpdate() throws Exception {
        persistence.mutate(BUYER,
                snapshot -> new Cart("cart-001", BUYER, List.of(), 0L, NOW, NOW));

        CountDownLatch barrier = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Cart> first = executor.submit(
                    () -> concurrentAdd("item-A", barrier));

            Future<Cart> second = executor.submit(
                    () -> concurrentAdd("item-B", barrier));

            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);

            Cart result = persistence.findByBuyerUserId(BUYER).orElseThrow();

            assertEquals(3L, result.version());
            assertEquals(2, result.items().size());
            assertTrue(result.items().stream().anyMatch(i -> i.itemId().equals("item-A")));
            assertTrue(result.items().stream().anyMatch(i -> i.itemId().equals("item-B")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldHandleConcurrentFirstCartCreation() throws Exception {
        CountDownLatch barrier = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Cart> first = executor.submit(
                    () -> createOrAdd("cart-A", "item-A", barrier));

            Future<Cart> second = executor.submit(
                    () -> createOrAdd("cart-B", "item-B", barrier));

            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);

            Cart result = persistence.findByBuyerUserId(BUYER).orElseThrow();

            assertEquals(1L, mongoTemplate.getCollection("carts").countDocuments());
            assertEquals(2L, result.version());
            assertEquals(2, result.items().size());
            assertTrue(result.items().stream().anyMatch(i -> i.itemId().equals("item-A")));
            assertTrue(result.items().stream().anyMatch(i -> i.itemId().equals("item-B")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectMutationChangingBuyer() {
        persistence.mutate(BUYER,
                snapshot -> new Cart("cart-001", BUYER, List.of(), 0L, NOW, NOW));

        assertThrows(IllegalArgumentException.class, () ->
                persistence.mutate(BUYER, snapshot -> {
                    Cart current = snapshot.orElseThrow();
                    return new Cart(
                            current.id(), "buyer-other", List.of(item("item-001")),
                            current.version(), current.createdAt(), current.updatedAt()
                    );
                })
        );

        assertTrue(persistence.findByBuyerUserId("buyer-other").isEmpty());
        assertEquals(1L, persistence.findByBuyerUserId(BUYER).orElseThrow().version());
    }

    private Cart concurrentAdd(String itemId, CountDownLatch barrier) {
        return persistence.mutate(BUYER, snapshot -> {
            Cart current = snapshot.orElseThrow();

            if (current.version() == 1L) {
                barrier.countDown();
                await(barrier);
            }

            return append(current, item(itemId));
        });
    }

    private Cart createOrAdd(String cartId, String itemId, CountDownLatch barrier) {
        return persistence.mutate(BUYER, snapshot -> {
            if (snapshot.isEmpty()) {
                barrier.countDown();
                await(barrier);
                return new Cart(cartId, BUYER, List.of(item(itemId)), 0L, NOW, NOW);
            }
            return append(snapshot.orElseThrow(), item(itemId));
        });
    }

    private Cart append(Cart current, CartItem newItem) {
        List<CartItem> items = new ArrayList<>(current.items());
        items.add(newItem);

        return new Cart(
                current.id(), current.buyerUserId(), items,
                current.version(), current.createdAt(), current.updatedAt()
        );
    }

    private CartItem item(String id) {
        return new CartItem(
                id, "product-" + id, "sku-" + id, "shop-001",
                1, 1L, "Product " + id, "Default",
                100000L, true, NOW, NOW
        );
    }

    private void await(CountDownLatch barrier) {
        try {
            if (!barrier.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test barrier timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted", ex);
        }
    }
}

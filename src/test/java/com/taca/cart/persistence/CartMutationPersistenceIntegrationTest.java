
package com.taca.cart.persistence;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartMutationService;
import com.taca.cart.application.UuidV7Generator;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cart.indexes.initialize=true"
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartMutationPersistenceIntegrationTest {
    private static final String BUYER = "buyer-001";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
    }

    @Autowired
    private CartMutationService service;

    @Autowired
    private CartCasPersistence persistence;

    @Autowired
    private CartPersistenceMapper mapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private UuidV7Generator idGenerator;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys").deleteMany(new Document());
    }

    @Test
    void shouldCreateAndPersistCartWithEmbeddedItem() {
        Cart created = service.addItem(BUYER, sku("sku-001"), 2);
        Cart saved = saved(BUYER);

        assertEquals(created, saved);
        assertEquals(1L, saved.version());
        assertEquals(1, saved.items().size());
        assertEquals(2, saved.items().getFirst().quantity());
        assertEquals(1L, saved.items().getFirst().itemRevision());
        assertEquals(7, UUID.fromString(saved.id()).version());
        assertEquals(7, UUID.fromString(saved.items().getFirst().itemId()).version());

        Document raw = rawCart(saved.id());
        assertEquals(BUYER, raw.getString("buyer_user_id"));
        assertEquals(1L, raw.getLong("version"));

        Document item = raw.getList("items", Document.class).getFirst();
        assertEquals(saved.items().getFirst().itemId(), item.getString("item_id"));
        assertEquals("sku-001", item.getString("sku_id"));
        assertEquals(2, item.getInteger("quantity"));
        assertEquals(1L, item.getLong("item_revision"));
        assertEquals(100000L, item.getLong("unit_price_snapshot"));
        assertNotNull(item.getDate("added_at"));
        assertNotNull(item.getDate("updated_at"));

        assertFalse(mongoTemplate.collectionExists("cart_items"));
        assertEquals(0L, mongoTemplate.getCollection("cart_idempotency_keys").countDocuments());
    }

    @Test
    void shouldMergeSameSkuAndIncreaseRevision() {
        Cart first = service.addItem(BUYER, sku("sku-001"), 2);
        String itemId = first.items().getFirst().itemId();

        Cart second = service.addItem(BUYER, sku("sku-001"), 3);
        Cart saved = saved(BUYER);

        assertEquals(second, saved);
        assertEquals(1, saved.items().size());
        assertEquals(5, saved.items().getFirst().quantity());
        assertEquals(2L, saved.items().getFirst().itemRevision());
        assertEquals(2L, saved.version());
        assertEquals(itemId, saved.items().getFirst().itemId());
        assertEquals(first.items().getFirst().addedAt(), saved.items().getFirst().addedAt());
        assertTrue(saved.items().getFirst().updatedAt().isAfter(first.items().getFirst().updatedAt()));
    }

    @Test
    void shouldCapQuantityAndPreserveNoopState() {
        service.addItem(BUYER, sku("sku-001"), 998);
        Cart capped = service.addItem(BUYER, sku("sku-001"), 5);
        Cart unchanged = service.addItem(BUYER, sku("sku-001"), 1);
        Cart saved = saved(BUYER);

        assertEquals(999, saved.items().getFirst().quantity());
        assertEquals(2L, saved.items().getFirst().itemRevision());
        assertEquals(2L, saved.version());
        assertEquals(capped, unchanged);
        assertEquals(capped, saved);
        assertEquals(capped.updatedAt(), saved.updatedAt());
    }

    @Test
    void shouldUpdateQuantityAndIgnoreNoopPut() {
        Cart created = service.addItem(BUYER, sku("sku-001"), 2);
        String itemId = created.items().getFirst().itemId();

        Cart updated = service.updateQuantity(BUYER, itemId, 5);
        Cart unchanged = service.updateQuantity(BUYER, itemId, 5);
        Cart saved = saved(BUYER);

        assertEquals(5, saved.items().getFirst().quantity());
        assertEquals(2L, saved.items().getFirst().itemRevision());
        assertEquals(2L, saved.version());
        assertEquals(updated, unchanged);
        assertEquals(updated, saved);
        assertEquals(updated.items().getFirst().updatedAt(), saved.items().getFirst().updatedAt());
    }

    @Test
    void shouldDeleteThenReAddWithNewItemId() {
        Cart first = service.addItem(BUYER, sku("sku-001"), 2);
        String originalId = first.items().getFirst().itemId();

        Cart removed = service.removeItem(BUYER, originalId);
        assertTrue(removed.items().isEmpty());
        assertEquals(2L, removed.version());

        Cart readded = service.addItem(BUYER, sku("sku-001"), 1);
        Cart saved = saved(BUYER);

        assertEquals(3L, saved.version());
        assertEquals(1, saved.items().size());
        assertEquals(1L, saved.items().getFirst().itemRevision());
        assertNotEquals(originalId, saved.items().getFirst().itemId());
        assertEquals(readded, saved);
        assertEquals(1L, mongoTemplate.getCollection("carts").countDocuments());
    }

    @Test
    void shouldRejectHundredAndFirstDistinctSku() {
        seedFullCart();

        assertCartError(CartErrorCode.CART_TOO_MANY_ITEMS,
                () -> service.addItem(BUYER, sku("sku-101"), 1));

        Cart unchanged = saved(BUYER);
        assertEquals(100, unchanged.items().size());
        assertEquals(1L, unchanged.version());

        Cart updated = service.addItem(BUYER, sku("sku-0"), 1);
        assertEquals(100, updated.items().size());
        assertEquals(2, updated.items().getFirst().quantity());
        assertEquals(2L, updated.items().getFirst().itemRevision());
        assertEquals(2L, saved(BUYER).version());
    }

    @Test
    void shouldRejectCrossBuyerMutations() {
        Cart first = service.addItem(BUYER, sku("sku-001"), 2);
        service.addItem("buyer-002", sku("sku-002"), 1);
        String itemId = first.items().getFirst().itemId();

        assertCartError(CartErrorCode.CART_ITEM_NOT_FOUND,
                () -> service.updateQuantity("buyer-002", itemId, 5));
        assertCartError(CartErrorCode.CART_ITEM_NOT_FOUND,
                () -> service.removeItem("buyer-002", itemId));

        assertEquals(2, saved(BUYER).items().getFirst().quantity());
        assertEquals(1L, saved(BUYER).version());
        assertEquals(1L, saved("buyer-002").version());
        assertEquals(2L, mongoTemplate.getCollection("carts").countDocuments());
    }

    @Test
    void shouldRejectInvalidQuantityWithoutWriting() {
        assertCartError(CartErrorCode.CART_INVALID_INPUT,
                () -> service.addItem(BUYER, sku("sku-001"), 0));
        assertCartError(CartErrorCode.CART_INVALID_INPUT,
                () -> service.addItem(BUYER, sku("sku-001"), 1000));

        assertEquals(0L, mongoTemplate.getCollection("carts").countDocuments());
    }

    @Test
    void shouldHandleConcurrentFirstCartCreation() throws Exception {
        runConcurrently(
                () -> service.addItem(BUYER, sku("sku-A"), 1),
                () -> service.addItem(BUYER, sku("sku-B"), 1)
        );

        Cart saved = saved(BUYER);

        assertEquals(1L, mongoTemplate.getCollection("carts").countDocuments());
        assertEquals(2L, saved.version());
        assertEquals(2, saved.items().size());
        assertTrue(saved.items().stream().anyMatch(i -> i.skuId().equals("sku-A")));
        assertTrue(saved.items().stream().anyMatch(i -> i.skuId().equals("sku-B")));
    }

    @Test
    void shouldHandleConcurrentAddsToSameSku() throws Exception {
        service.addItem(BUYER, sku("sku-001"), 1);

        runConcurrently(
                () -> service.addItem(BUYER, sku("sku-001"), 2),
                () -> service.addItem(BUYER, sku("sku-001"), 3)
        );

        Cart saved = saved(BUYER);

        assertEquals(1, saved.items().size());
        assertEquals(6, saved.items().getFirst().quantity());
        assertEquals(3L, saved.items().getFirst().itemRevision());
        assertEquals(3L, saved.version());
    }

    @Test
    void shouldPersistUpdatedProductSnapshot() {
        service.addItem(BUYER, sku("sku-001"), 1);

        var changed = new ValidatedSkuSnapshot(
                "product-sku-001", "sku-001", "shop-001",
                "Updated product", "Blue / L", 120000L
        );

        service.addItem(BUYER, changed, 1);
        CartItem saved = saved(BUYER).items().getFirst();

        assertEquals(2, saved.quantity());
        assertEquals(2L, saved.itemRevision());
        assertEquals("Updated product", saved.titleSnapshot());
        assertEquals("Blue / L", saved.skuLabelSnapshot());
        assertEquals(120000L, saved.unitPriceSnapshot());
    }

    private Cart saved(String buyerUserId) {
        return persistence.findByBuyerUserId(buyerUserId).orElseThrow();
    }

    private Document rawCart(String cartId) {
        Document document = mongoTemplate.getCollection("carts")
                .find(new Document("_id", cartId))
                .first();
        assertNotNull(document);
        return document;
    }
    
    private ValidatedSkuSnapshot sku(String skuId) {
        return new ValidatedSkuSnapshot(
                "product-" + skuId, skuId, "shop-001",
                "Product " + skuId, "Default", 100000L
        );
    }

    private void seedFullCart() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        List<CartItem> items = IntStream.range(0, 100)
                .mapToObj(i -> new CartItem(
                        idGenerator.next(), "product-sku-" + i,
                        "sku-" + i, "shop-001",
                        1, 1L, "Product " + i, "Default",
                        100000L, true, now, now
                ))
                .toList();

        Cart cart = new Cart(idGenerator.next(), BUYER, items, 1L, now, now);
        mongoTemplate.insert(mapper.toDocument(cart));
    }

    private void assertCartError(CartErrorCode expected, Runnable action) {
        CartException exception = assertThrows(CartException.class, action::run);
        assertEquals(expected, exception.getCode());
    }

    private void runConcurrently(Callable<Cart> first, Callable<Cart> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Cart> firstResult = executor.submit(() -> {
                start.await();
                return first.call();
            });
            Future<Cart> secondResult = executor.submit(() -> {
                start.await();
                return second.call();
            });

            start.countDown();
            firstResult.get(30, TimeUnit.SECONDS);
            secondResult.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}

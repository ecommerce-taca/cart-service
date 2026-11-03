
package com.taca.cart.persistence;

import com.taca.cart.domain.*;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import com.taca.cart.persistence.repository.*;
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
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final String CART_ID = "019a0011-2222-7333-8444-555566667777";

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
    private CartMongoRepository cartRepository;

    @Autowired
    private CartIdempotencyMongoRepository idempotencyRepository;

    @Autowired
    private CartPersistenceMapper mapper;

    @BeforeEach
    void setUp() {
        cartRepository.deleteAll();
        idempotencyRepository.deleteAll();
    }

    @Test
    void shouldPersistAndRestoreCartDomain() {
        Cart cart = sampleCart();
        cartRepository.save(mapper.toDocument(cart));

        var saved = cartRepository.findById(CART_ID).orElseThrow();
        var restored = mapper.toDomain(saved);

        assertEquals(cart, restored);
        assertEquals("cartdb_test", mongoTemplate.getDb().getName());
    }

    @Test
    void shouldStoreCorrectCartBsonFields() {
        cartRepository.save(mapper.toDocument(sampleCart()));

        Document raw = mongoTemplate.getCollection("carts")
                .find(new Document("_id", CART_ID))
                .first();

        assertNotNull(raw);
        assertEquals(CART_ID, raw.getString("_id"));
        assertEquals("buyer-001", raw.getString("buyer_user_id"));
        assertInstanceOf(Long.class, raw.get("version"));
        assertEquals(Date.from(NOW), raw.getDate("created_at"));
        assertEquals(Date.from(NOW), raw.getDate("updated_at"));

        Document item = raw.getList("items", Document.class).getFirst();

        assertEquals("item-001", item.getString("item_id"));
        assertEquals("product-001", item.getString("product_id"));
        assertEquals("sku-001", item.getString("sku_id"));
        assertEquals("shop-001", item.getString("shop_id"));
        assertEquals(2, item.getInteger("quantity"));
        assertEquals(1L, item.getLong("item_revision"));
        assertEquals(249000L, item.getLong("unit_price_snapshot"));
        assertEquals(true, item.getBoolean("available"));
        assertEquals(Date.from(NOW), item.getDate("added_at"));

        assertFalse(mongoTemplate.collectionExists("cart_items"));
    }

    @Test
    void shouldFindCartByBuyerUserId() {
        cartRepository.save(mapper.toDocument(sampleCart()));

        assertTrue(cartRepository.findByBuyerUserId("buyer-001").isPresent());
        assertTrue(cartRepository.findByBuyerUserId("buyer-other").isEmpty());
    }

    @Test
    void shouldPersistCompositeIdAndOriginalResponse() {
        var key = sampleKey("buyer-001", "add-item-001");
        idempotencyRepository.save(mapper.toDocument(key));

        var id = mapper.toDocument(key).id();
        var saved = idempotencyRepository.findById(id).orElseThrow();

        assertEquals(key, mapper.toDomain(saved));

        Document raw = mongoTemplate.getCollection("cart_idempotency_keys")
                .find(new Document("_id", new Document("buyer_user_id", "buyer-001")
                        .append("key", "add-item-001")))
                .first();

        assertNotNull(raw);
        Document bsonId = raw.get("_id", Document.class);

        assertEquals(List.of("buyer_user_id", "key"),
                new ArrayList<>(bsonId.keySet()));
        assertEquals("a".repeat(64), raw.getString("request_hash"));
        assertEquals(201, raw.getInteger("response_status"));
        assertEquals(Date.from(NOW.plusSeconds(86400)), raw.getDate("expires_at"));

        Document response = raw.get("response", Document.class);
        Document meta = response.get("meta", Document.class);

        assertEquals("req-001", meta.getString("request_id"));
    }

    @Test
    void shouldScopeIdempotencyKeyByBuyer() {
        var first = sampleKey("buyer-001", "same-key");
        var second = sampleKey("buyer-002", "same-key");

        idempotencyRepository.save(mapper.toDocument(first));
        idempotencyRepository.save(mapper.toDocument(second));

        assertEquals(2, idempotencyRepository.count());
        assertTrue(idempotencyRepository.findById(mapper.toDocument(first).id()).isPresent());
        assertTrue(idempotencyRepository.findById(mapper.toDocument(second).id()).isPresent());
    }

    private Cart sampleCart() {
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 1, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW
        );

        return new Cart(
                CART_ID, "buyer-001", List.of(item),
                0, NOW, NOW
        );
    }

    private CartIdempotencyKey sampleKey(String buyer, String key) {
        Map<String, Object> response = Map.of(
                "data", Map.of(
                        "cart_id", CART_ID,
                        "item_id", "item-001",
                        "quantity", 2,
                        "line_total", 498000L
                ),
                "meta", Map.of("request_id", "req-001")
        );

        return new CartIdempotencyKey(
                new CartIdempotencyId(buyer, key),
                "a".repeat(64),
                response,
                201,
                NOW.plusSeconds(86400),
                NOW
        );
    }
}


package com.taca.cart.persistence;

import com.taca.cart.persistence.document.CartDocument;
import com.taca.cart.persistence.index.CartIndexInitializer;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cart.indexes.initialize=true"
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartIndexIntegrationTest {
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
    private CartIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys").deleteMany(new Document());
    }

    @Test
    void shouldCreateCartIndexesWithoutTtl() {
        Document unique = findIndex("carts", "ux_carts_buyer_user_id");
        Document updated = findIndex("carts", "idx_carts_updated_at");

        assertEquals(new Document("buyer_user_id", 1), unique.get("key", Document.class));
        assertEquals(Boolean.TRUE, unique.getBoolean("unique"));
        assertEquals(new Document("updated_at", 1), updated.get("key", Document.class));
        assertEquals(3, countIndexes("carts"));

        for (Document index : mongoTemplate.getCollection("carts").listIndexes()) {
            assertFalse(index.containsKey("expireAfterSeconds"));
        }
    }

    @Test
    void shouldCreateIdempotencyTtlIndex() {
        Document ttl = findIndex(
                "cart_idempotency_keys",
                "ttl_cart_idempotency_keys_expires_at"
        );

        assertEquals(new Document("expires_at", 1), ttl.get("key", Document.class));
        assertEquals(0L, ((Number) ttl.get("expireAfterSeconds")).longValue());
        assertNotNull(findIndex("cart_idempotency_keys", "_id_"));
        assertEquals(2, countIndexes("cart_idempotency_keys"));
    }

    @Test
    void shouldRejectDuplicateBuyerCart() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");

        mongoTemplate.insert(new CartDocument(
                "019a0011-2222-7333-8444-555566667771",
                "buyer-001", List.of(), 0, now, now
        ));

        assertThrows(DuplicateKeyException.class, () ->
                mongoTemplate.insert(new CartDocument(
                        "019a0011-2222-7333-8444-555566667772",
                        "buyer-001", List.of(), 0, now, now
                ))
        );
    }

    @Test
    void shouldInitializeIndexesAgainWithoutDeletingData() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        String id = "019a0011-2222-7333-8444-555566667773";

        mongoTemplate.insert(new CartDocument(
                id, "buyer-002", List.of(), 0, now, now
        ));

        initializer.ensureIndexes();
        initializer.ensureIndexes();

        assertEquals(3, countIndexes("carts"));
        assertEquals(2, countIndexes("cart_idempotency_keys"));
        assertNotNull(mongoTemplate.findById(id, CartDocument.class));
    }

    private Document findIndex(String collection, String name) {
        for (Document index : mongoTemplate.getCollection(collection).listIndexes()) {
            if (name.equals(index.getString("name"))) return index;
        }
        throw new AssertionError("Index not found: " + collection + "." + name);
    }

    private int countIndexes(String collection) {
        int count = 0;
        for (Document ignored : mongoTemplate.getCollection(collection).listIndexes()) {
            count++;
        }
        return count;
    }
}

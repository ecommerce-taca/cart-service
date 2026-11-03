
package com.taca.cart.persistence;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MongoTransactionIntegrationTest {
    private static final String CARTS = "phase1f_carts";
    private static final String KEYS = "phase1f_idempotency_keys";

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
    private MongoTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(CARTS);
        mongoTemplate.dropCollection(KEYS);
        mongoTemplate.createCollection(CARTS);
        mongoTemplate.createCollection(KEYS);
    }

    @Test
    void shouldConnectToTestDatabase() {
        assertEquals("cartdb_test", mongoTemplate.getDb().getName());

        Document result = mongoTemplate.executeCommand(new Document("ping", 1));

        assertEquals(1.0, ((Number) result.get("ok")).doubleValue());
    }
    
    @Test
    void shouldUseWritableReplicaSetPrimary() {
        Document result = mongoTemplate.executeCommand(new Document("hello", 1));

        String replicaSetName = result.getString("setName");

        assertNotNull(replicaSetName);
        assertFalse(replicaSetName.isBlank());
        assertEquals(Boolean.TRUE, result.getBoolean("isWritablePrimary"));
    }


    @Test
    void shouldCommitBothCollections() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            mongoTemplate.insert(
                    new Document("_id", "cart-commit")
                            .append("buyer_user_id", "buyer-001"),
                    CARTS
            );

            mongoTemplate.insert(
                    new Document("_id", "key-commit")
                            .append("request_hash", "hash-001"),
                    KEYS
            );
        });

        assertNotNull(mongoTemplate.findById("cart-commit", Document.class, CARTS));
        assertNotNull(mongoTemplate.findById("key-commit", Document.class, KEYS));
    }

    @Test
    void shouldRollbackBothCollectionsOnFailure() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () ->
                transaction.executeWithoutResult(status -> {
                    mongoTemplate.insert(
                            new Document("_id", "cart-rollback")
                                    .append("buyer_user_id", "buyer-002"),
                            CARTS
                    );

                    mongoTemplate.insert(
                            new Document("_id", "key-rollback")
                                    .append("request_hash", "hash-002"),
                            KEYS
                    );

                    throw new IllegalStateException("Force transaction rollback");
                })
        );

        assertNull(mongoTemplate.findById("cart-rollback", Document.class, CARTS));
        assertNull(mongoTemplate.findById("key-rollback", Document.class, KEYS));
    }
}

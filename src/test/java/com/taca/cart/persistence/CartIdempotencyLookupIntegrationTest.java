package com.taca.cart.persistence;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartIdempotencyLookupService;
import com.taca.cart.domain.CartIdempotencyId;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import com.taca.cart.persistence.repository.CartIdempotencyMongoRepository;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cart.indexes.initialize=false"
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartIdempotencyLookupIntegrationTest {
    private static final String BUYER = "buyer-001";
    private static final String KEY = "add-item-001";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
    }

    @Autowired
    private CartIdempotencyLookupService lookupService;

    @Autowired
    private CartIdempotencyMongoRepository repository;

    @Autowired
    private CartPersistenceMapper mapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void shouldReadCommittedResponseWithoutWriting() {
        save(BUYER, KEY, HASH, Instant.now().plusSeconds(3600));
        Document before = rawRecord(BUYER, KEY);

        CartIdempotencyKey replay = lookupService
                .findReplay(BUYER, KEY, HASH).orElseThrow();

        Document after = rawRecord(BUYER, KEY);

        assertEquals(before, after);
        assertEquals(201, replay.responseStatus());
        assertEquals("req-original-001",
                ((Map<?, ?>) replay.response().get("meta")).get("request_id"));
        assertEquals(1L, repository.count());
    }

    @Test
    void shouldReturnConflictForDifferentHash() {
        save(BUYER, KEY, HASH, Instant.now().plusSeconds(3600));

        CartException ex = assertThrows(CartException.class,
                () -> lookupService.findReplay(BUYER, KEY, OTHER_HASH));

        assertEquals(CartErrorCode.CART_IDEMPOTENCY_CONFLICT, ex.getCode());
        assertEquals(1L, repository.count());
    }

    @Test
    void shouldAllowExpiredKeyEvenWhenDocumentStillExists() {
        save(BUYER, KEY, HASH, Instant.now().minusSeconds(60));

        assertTrue(lookupService.findReplay(BUYER, KEY, OTHER_HASH).isEmpty());
        assertEquals(1L, repository.count());
        assertNotNull(rawRecord(BUYER, KEY));
    }

    @Test
    void shouldKeepKeysSeparateBetweenBuyers() {
        save(BUYER, KEY, HASH, Instant.now().plusSeconds(3600));
        save("buyer-002", KEY, OTHER_HASH, Instant.now().plusSeconds(3600));

        assertTrue(lookupService.findReplay(BUYER, KEY, HASH).isPresent());
        assertTrue(lookupService.findReplay("buyer-002", KEY, OTHER_HASH).isPresent());
        assertEquals(2L, repository.count());

        CartException ex = assertThrows(CartException.class,
                () -> lookupService.findReplay("buyer-002", KEY, HASH));

        assertEquals(CartErrorCode.CART_IDEMPOTENCY_CONFLICT, ex.getCode());
    }

    @Test
    void shouldReturnEmptyWhenKeyIsMissing() {
        assertTrue(lookupService.findReplay(BUYER, KEY, HASH).isEmpty());
        assertEquals(0L, repository.count());
    }

    private void save(String buyer, String key, String hash, Instant expiresAt) {
        Map<String, Object> response = Map.of(
                "data", Map.of(
                        "cart_id", "cart-001",
                        "item_id", "item-001",
                        "quantity", 2,
                        "line_total", 498000L),
                "meta", Map.of("request_id", "req-original-001"));

        CartIdempotencyKey record = new CartIdempotencyKey(
                new CartIdempotencyId(buyer, key),
                hash, response, 201,
                expiresAt, Instant.now().minusSeconds(86400));

        repository.save(mapper.toDocument(record));
    }

    private Document rawRecord(String buyer, String key) {
        Document id = new Document("buyer_user_id", buyer).append("key", key);
        Document record = mongoTemplate.getCollection("cart_idempotency_keys")
                .find(new Document("_id", id)).first();

        assertNotNull(record);
        return record;
    }
}

package com.taca.cart.persistence;

import com.mongodb.MongoException;
import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartIdempotencyLookupService;
import com.taca.cart.application.CartIdempotencyTransactionService;
import com.taca.cart.application.CartRequestHasher;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.config.MongoCommitRetryExecutor;
import com.taca.cart.config.RetryingMongoTransactionManager;
import com.taca.cart.domain.CartIdempotencyKey;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cart.indexes.initialize=false"
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartUnknownCommitIntegrationTest {
    private static final String BUYER = "buyer-001";
    private static final String KEY = "add-item-001";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
    }

    @Autowired
    private MongoTransactionManager transactionManager;

    @Autowired
    private CartIdempotencyTransactionService transactionService;

    @Autowired
    private CartIdempotencyLookupService lookupService;

    @Autowired
    private CartRequestHasher hasher;

    @Autowired
    private MongoTemplate mongoTemplate;

    @MockitoBean
    private MongoCommitRetryExecutor commitRetryExecutor;

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

        doAnswer(invocation -> {
            Runnable commit = invocation.getArgument(0);
            commit.run();
            return null;
        }).when(commitRetryExecutor).execute(any(Runnable.class));
    }

    @Test
    void shouldUseRetryingMongoTransactionManager() {
        assertInstanceOf(RetryingMongoTransactionManager.class, transactionManager);
    }

    @Test
    void shouldCommitNormally() {
        CartIdempotencyKey result = execute("req-normal");

        assertEquals(201, result.responseStatus());
        assertEquals(1L, countCarts());
        assertEquals(1L, countKeys());
        assertEquals(2, quantity());
        assertEquals(1L, version());
    }

    @Test
    void shouldRecoverWhenCommitSucceededButAcknowledgementWasLost() {
        doAnswer(invocation -> {
            Runnable commit = invocation.getArgument(0);
            commit.run();
            throw unknownCommit();
        }).when(commitRetryExecutor).execute(any(Runnable.class));

        CartIdempotencyKey result = execute("req-original");

        assertEquals(201, result.responseStatus());
        assertEquals(1L, countCarts());
        assertEquals(1L, countKeys());
        assertEquals(2, quantity());
        assertEquals(1L, version());

        Map<?, ?> meta = (Map<?, ?>) result.response().get("meta");
        assertEquals("req-original", meta.get("request_id"));

        CartIdempotencyKey replay = lookupService.findReplay(
                BUYER, KEY, hasher.hash(request())).orElseThrow();

        assertEquals(result.response(), replay.response());
        verify(commitRetryExecutor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void shouldNotRetryMutationWhenCommitOutcomeIsUnconfirmed() {
        doAnswer(invocation -> {
            throw unknownCommit();
        }).when(commitRetryExecutor).execute(any(Runnable.class));

        CartException ex = assertThrows(CartException.class,
                () -> execute("req-unknown"));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
        assertTrue(ex.getMessage().contains("outcome is unknown"));

        assertEquals(0L, countCarts());
        assertEquals(0L, countKeys());

        verify(commitRetryExecutor, times(1)).execute(any(Runnable.class));
    }

    private CartIdempotencyKey execute(String requestId) {
        return transactionService.execute(
                BUYER, KEY, request(), sku(), requestId);
    }

    private AddCartItemRequest request() {
        return new AddCartItemRequest("product-001", "sku-001", 2);
    }

    private ValidatedSkuSnapshot sku() {
        return new ValidatedSkuSnapshot(
                "product-001", "sku-001", "shop-001",
                "Cotton jacket", "Black / L", 249000L);
    }

    private MongoException unknownCommit() {
        MongoException ex = new MongoException(91, "Simulated lost commit acknowledgement");
        ex.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
        return ex;
    }

    private Document rawCart() {
        Document cart = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", BUYER)).first();
        assertNotNull(cart);
        return cart;
    }

    private int quantity() {
        return rawCart().getList("items", Document.class)
                .getFirst().getInteger("quantity");
    }

    private long version() {
        return rawCart().getLong("version");
    }

    private long countCarts() {
        return mongoTemplate.getCollection("carts").countDocuments();
    }

    private long countKeys() {
        return mongoTemplate.getCollection("cart_idempotency_keys").countDocuments();
    }
}

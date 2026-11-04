package com.taca.cart.persistence;

import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.application.CartQueryService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cart.indexes.initialize=false"
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartQueryPersistenceIntegrationTest {
    private static final String BUYER = "buyer-001";
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
    }

    @Autowired
    private CartQueryService queryService;

    @Autowired
    private CartPersistenceMapper mapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
    }

    @Test
    void shouldReturnEmptyWithoutCreatingCart() {
        CartResponse first = queryService.getCart(BUYER);
        CartResponse second = queryService.getCart(BUYER);

        assertNull(first.cartId());
        assertNull(first.updatedAt());
        assertTrue(first.items().isEmpty());
        assertEquals(0, first.itemCount());
        assertEquals(first, second);

        assertEquals(0L, mongoTemplate.getCollection("carts").countDocuments());
    }

    @Test
    void shouldReadPersistedCartWithoutChangingDocument() {
        mongoTemplate.insert(mapper.toDocument(sampleCart()));

        Document before = rawCart("cart-001");

        CartResponse first = queryService.getCart(BUYER);
        CartResponse second = queryService.getCart(BUYER);

        Document after = rawCart("cart-001");

        assertEquals(first, second);
        assertEquals(before, after);
        assertEquals("cart-001", first.cartId());
        assertEquals(1, first.itemCount());
        assertEquals(2, first.items().getFirst().quantity());
        assertEquals(498000L, first.items().getFirst().lineTotal());
        assertEquals(5L, after.getLong("version"));
        assertEquals(1L, mongoTemplate.getCollection("carts").countDocuments());

        Document item = after.getList("items", Document.class).getFirst();
        assertEquals(3L, item.getLong("item_revision"));
        assertEquals(2, item.getInteger("quantity"));
    }

    @Test
    void shouldNotReadAnotherBuyersCart() {
        mongoTemplate.insert(mapper.toDocument(sampleCart()));

        CartResponse otherBuyer = queryService.getCart("buyer-002");
        CartResponse actualBuyer = queryService.getCart(BUYER);

        assertNull(otherBuyer.cartId());
        assertEquals(0, otherBuyer.itemCount());
        assertEquals("cart-001", actualBuyer.cartId());
        assertEquals(1L, mongoTemplate.getCollection("carts").countDocuments());
    }

    private Cart sampleCart() {
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 3L, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW
        );

        return new Cart("cart-001", BUYER, List.of(item), 5L, NOW, NOW);
    }

    private Document rawCart(String cartId) {
        Document document = mongoTemplate.getCollection("carts")
                .find(new Document("_id", cartId))
                .first();

        assertNotNull(document);
        return document;
    }
}
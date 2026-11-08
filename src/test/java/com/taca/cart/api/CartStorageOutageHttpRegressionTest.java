package com.taca.cart.api;

import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.integrations.ProductHydrationClient;
import com.taca.cart.persistence.CartCasPersistence;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "cart.indexes.initialize=false"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartStorageOutageHttpRegressionTest {
    private static final String BUYER = "buyer-001";
    private static final String CART_ID = "cart-buyer-001";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final String CART_PATH = "/api/v1/cart";
    private static final String INTERNAL_PATH = "/api/v1/internal/carts/" + BUYER;

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("product.base-url", () -> "");
        registry.add("inventory.base-url", () -> "");
        registry.add("management.tracing.sampling.probability", () -> 1.0);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CartPersistenceMapper mapper;

    @MockitoSpyBean
    private CartCasPersistence persistence;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @MockitoBean
    private ProductHydrationClient productHydrationClient;

    @MockitoBean
    private InventoryAvailabilityClient inventoryClient;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys")
                .deleteMany(new Document());
        mongoTemplate.insert(mapper.toDocument(sampleCart()));
    }

    @Test
    void shouldReturn503ForBuyerGetWhenMongoReadFails() throws Exception {
        failRead();

        var result = mvc.perform(get(CART_PATH)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .header("X-Request-ID", "req-mongo-get"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Request-ID", "req-mongo-get"))
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString()
                .contains("MongoDB connection secret"));
        assertEquals(3L, rawCart().getLong("version"));
        verifyNoInteractions(productHydrationClient, inventoryClient);
    }

    @Test
    void shouldReturn503ForInternalGetWhenMongoReadFails()
            throws Exception {
        failRead();

        mvc.perform(get(INTERNAL_PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"));

        assertEquals(3L, rawCart().getLong("version"));
        verifyNoInteractions(productHydrationClient, inventoryClient);
    }

    @Test
    void shouldReturn503ForBuyerPostWithoutChangingCart()
            throws Exception {
        Document before = rawCart();

        when(productCatalogClient.validateSku("product-002", "sku-002"))
                .thenReturn(new ValidatedSkuSnapshot(
                        "product-002", "sku-002", "shop-001",
                        "Product 002", "Default", 199000L));
        failMutation();

        mvc.perform(post(CART_PATH + "/items")
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_id":"product-002",
                                 "sku_id":"sku-002","quantity":1}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"));

        assertEquals(before, rawCart());
        verify(productCatalogClient)
                .validateSku("product-002", "sku-002");
    }

    @Test
    void shouldReturn503ForBuyerPutWithoutChangingCart()
            throws Exception {
        Document before = rawCart();
        failMutation();

        mvc.perform(put(CART_PATH + "/items/item-001")
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":7}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"));

        assertEquals(before, rawCart());
    }

    @Test
    void shouldReturn503ForBuyerDeleteWithoutChangingCart()
            throws Exception {
        Document before = rawCart();
        failMutation();

        mvc.perform(delete(CART_PATH + "/items/item-001")
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"));

        assertEquals(before, rawCart());
    }

    @Test
    void shouldReturn503ForCheckoutCleanupWithoutDeletingItems()
            throws Exception {
        Document before = rawCart();
        failMutation();

        mvc.perform(post(INTERNAL_PATH + "/checked-out")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cart_id":"cart-buyer-001",
                                  "checkout_group_id":"group-001",
                                  "item_ids":["item-001"],
                                  "expected_item_revisions":{"item-001":3}
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"));

        assertEquals(before, rawCart());
        assertEquals(1, rawCart().getList("items", Document.class).size());
    }

    @Test
    void shouldRecoverAfterTransientMongoReadFailure()
            throws Exception {
        Document before = rawCart();

        doThrow(storageFailure()).doCallRealMethod()
                .when(persistence).findByBuyerUserId(BUYER);

        mvc.perform(get(INTERNAL_PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isServiceUnavailable());

        mvc.perform(get(INTERNAL_PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value(CART_ID))
                .andExpect(jsonPath("$.data.items[0].item_id")
                        .value("item-001"))
                .andExpect(jsonPath("$.data.items[0].item_revision")
                        .value(3));

        assertEquals(before, rawCart());
        verify(persistence, times(2)).findByBuyerUserId(BUYER);
    }

    @Test
    void shouldRejectUnauthorizedRequestBeforeMongoAccess()
            throws Exception {
        failRead();

        mvc.perform(get(INTERNAL_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_UNAUTHENTICATED"));

        verify(persistence, never()).findByBuyerUserId(BUYER);
        assertEquals(3L, rawCart().getLong("version"));
    }

    private void failRead() {
        doThrow(storageFailure()).when(persistence)
                .findByBuyerUserId(BUYER);
    }

    private void failMutation() {
        doThrow(storageFailure()).when(persistence)
                .mutate(eq(BUYER), any());
    }

    private DataAccessResourceFailureException storageFailure() {
        return new DataAccessResourceFailureException(
                "MongoDB connection secret");
    }

    private Cart sampleCart() {
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 3L, "Stored title", "Black / L",
                249000L, true, NOW, NOW);

        return new Cart(CART_ID, BUYER,
                List.of(item), 3L, NOW, NOW);
    }

    private Document rawCart() {
        Document result = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", BUYER))
                .first();
        assertNotNull(result);
        return result;
    }
}

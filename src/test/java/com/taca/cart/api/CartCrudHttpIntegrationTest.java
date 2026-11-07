package com.taca.cart.api;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.persistence.CartCasPersistence;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "cart.indexes.initialize=true"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartCrudHttpIntegrationTest {
    private static final String CART_PATH = "/api/v1/cart";
    private static final String BUYER = "buyer-001";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("product.base-url", () -> "");
        registry.add("inventory.base-url", () -> "");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CartPersistenceMapper mapper;

    @Autowired
    private CartCasPersistence persistence;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys").deleteMany(new Document());
    }

    @Test
    void shouldReturnEmptyCartWithoutCreatingDocument() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(CART_PATH).with(gateway(BUYER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cart_id").value(nullValue()))
                    .andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(jsonPath("$.data.item_count").value(0))
                    .andExpect(jsonPath("$.data.updated_at").value(nullValue()));
        }

        assertEquals(0L, countCarts());
        verifyNoInteractions(productCatalogClient);
    }

    @Test
    void shouldCreateCartAndReadPersistedItem() throws Exception {
        MvcResult result = postItem(BUYER, "sku-001", 2);
        JsonNode data = data(result);
        String cartId = data.path("cart_id").asString();
        String itemId = data.path("item_id").asString();

        assertEquals(2, data.path("quantity").asInt());
        assertEquals(498000L, data.path("line_total").asLong());

        mvc.perform(get(CART_PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value(cartId))
                .andExpect(jsonPath("$.data.item_count").value(1))
                .andExpect(jsonPath("$.data.items[0].item_id").value(itemId))
                .andExpect(jsonPath("$.data.items[0].sku_id").value("sku-001"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].line_total").value(498000))
                .andExpect(jsonPath("$.data.items[0].availability_status").value("STALE"));

        Document stored = rawCart(BUYER);
        assertEquals(cartId, stored.getString("_id"));
        assertEquals(1L, stored.getLong("version"));
        assertEquals(1L, item(stored, 0).getLong("item_revision"));
        assertEquals(249000L, item(stored, 0).getLong("unit_price_snapshot"));
        assertEquals(1L, countCarts());
    }

    @Test
    void shouldMergeDuplicateSkuWithoutCreatingSecondItem() throws Exception {
        MvcResult first = postItem(BUYER, "sku-001", 2);
        MvcResult second = postItem(BUYER, "sku-001", 3);

        assertEquals(data(first).path("cart_id").asString(),
                data(second).path("cart_id").asString());
        assertEquals(data(first).path("item_id").asString(),
                data(second).path("item_id").asString());
        assertEquals(5, data(second).path("quantity").asInt());
        assertEquals(1245000L, data(second).path("line_total").asLong());

        Document stored = rawCart(BUYER);
        assertEquals(1, stored.getList("items", Document.class).size());
        assertEquals(5, item(stored, 0).getInteger("quantity"));
        assertEquals(2L, item(stored, 0).getLong("item_revision"));
        assertEquals(2L, stored.getLong("version"));
    }

    @Test
    void shouldCapQuantityAndPreserveNoopState() throws Exception {
        postItem(BUYER, "sku-001", 998);
        MvcResult capped = postItem(BUYER, "sku-001", 5);

        assertEquals(999, data(capped).path("quantity").asInt());
        assertEquals(248751000L, data(capped).path("line_total").asLong());

        Document before = rawCart(BUYER);
        MvcResult noop = postItem(BUYER, "sku-001", 1);
        Document after = rawCart(BUYER);

        assertEquals(999, data(noop).path("quantity").asInt());
        assertEquals(before, after);
        assertEquals(2L, after.getLong("version"));
        assertEquals(2L, item(after, 0).getLong("item_revision"));
    }

    @Test
    void shouldReplaceQuantityAndPreserveNoopPut() throws Exception {
        String itemId = data(postItem(BUYER, "sku-001", 2))
                .path("item_id").asString();

        MvcResult updated = mvc.perform(put(CART_PATH + "/items/" + itemId)
                        .with(gateway(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_id").value(itemId))
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andExpect(jsonPath("$.data.line_total").value(1245000))
                .andReturn();

        assertEquals(5, data(updated).path("quantity").asInt());

        Document before = rawCart(BUYER);
        assertEquals(2L, before.getLong("version"));
        assertEquals(2L, item(before, 0).getLong("item_revision"));

        mvc.perform(put(CART_PATH + "/items/" + itemId)
                        .with(gateway(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isOk());

        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldDeleteOnlyRequestedItemAndKeepEmptyCart() throws Exception {
        String firstId = data(postItem(BUYER, "sku-001", 2))
                .path("item_id").asString();
        String secondId = data(postItem(BUYER, "sku-002", 3))
                .path("item_id").asString();

        mvc.perform(delete(CART_PATH + "/items/" + firstId)
                        .with(gateway(BUYER)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mvc.perform(get(CART_PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_count").value(1))
                .andExpect(jsonPath("$.data.items[0].item_id").value(secondId))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3));

        mvc.perform(delete(CART_PATH + "/items/" + firstId)
                        .with(gateway(BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));

        mvc.perform(delete(CART_PATH + "/items/" + secondId)
                        .with(gateway(BUYER)))
                .andExpect(status().isNoContent());

        Document stored = rawCart(BUYER);
        assertTrue(stored.getList("items", Document.class).isEmpty());
        assertEquals(4L, stored.getLong("version"));
        assertEquals(1L, countCarts());
    }

    @Test
    void shouldIsolateCartsBetweenBuyers() throws Exception {
        String buyerA = "buyer-A";
        String buyerB = "buyer-B";
        String itemA = data(postItem(buyerA, "sku-001", 2))
                .path("item_id").asString();
        String itemB = data(postItem(buyerB, "sku-001", 3))
                .path("item_id").asString();

        assertNotEquals(itemA, itemB);
        assertEquals(2L, countCarts());

        mvc.perform(put(CART_PATH + "/items/" + itemA)
                        .with(gateway(buyerB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));

        mvc.perform(delete(CART_PATH + "/items/" + itemA)
                        .with(gateway(buyerB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));

        mvc.perform(get(CART_PATH).with(gateway(buyerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].item_id").value(itemA))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));

        mvc.perform(get(CART_PATH).with(gateway(buyerB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].item_id").value(itemB))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3));

        assertEquals(1L, rawCart(buyerA).getLong("version"));
        assertEquals(1L, rawCart(buyerB).getLong("version"));
    }

    @Test
    void shouldRejectInvalidAndUnauthenticatedRequestsWithoutWriting() throws Exception {
        for (String quantity : List.of("0", "1000", "2.5", "null")) {
            mvc.perform(post(CART_PATH + "/items")
                            .with(gateway(BUYER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"product_id":"product-001","sku_id":"sku-001","quantity":%s}
                                    """.formatted(quantity)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
        }

        mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_id":"product-001","sku_id":"sku-001",
                                 "quantity":2,"buyer_user_id":"admin"}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post(CART_PATH + "/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_id":"product-001","sku_id":"sku-001","quantity":2}
                                """))
                .andExpect(status().isUnauthorized());

        assertEquals(0L, countCarts());
        verifyNoInteractions(productCatalogClient);
    }

    @Test
    void shouldNotWriteWhenProductCatalogRejectsSku() throws Exception {
        when(productCatalogClient.validateSku("product-001", "sku-blocked"))
                .thenThrow(new CartException(
                        CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                        "Product or SKU is unavailable"));

        mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_id":"product-001","sku_id":"sku-blocked","quantity":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_PRODUCT_UNAVAILABLE"));

        when(productCatalogClient.validateSku("product-001", "sku-error"))
                .thenThrow(new CartException(
                        CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                        "Product Catalog is unavailable"));

        mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_id":"product-001","sku_id":"sku-error","quantity":2}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CART_DEPENDENCY_UNAVAILABLE"));

        assertEquals(0L, countCarts());
    }

    @Test
    void shouldRejectHundredAndFirstItemButAllowExistingSku() throws Exception {
        List<CartItem> items = IntStream.range(0, 100)
                .mapToObj(i -> new CartItem(
                        "seed-item-" + i, "product-001", "sku-" + i, "shop-001",
                        1, 1L, "Product", "Default",
                        1000L, true, NOW, NOW))
                .toList();

        Cart seeded = new Cart("cart-full", BUYER, items, 1L, NOW, NOW);
        mongoTemplate.insert(mapper.toDocument(seeded));

        when(productCatalogClient.validateSku("product-001", "sku-new"))
                .thenReturn(sku("sku-new"));

        mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_id":"product-001","sku_id":"sku-new","quantity":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_TOO_MANY_ITEMS"));

        assertEquals(100, rawCart(BUYER).getList("items", Document.class).size());
        assertEquals(1L, rawCart(BUYER).getLong("version"));

        MvcResult merged = postItem(BUYER, "sku-0", 1);

        assertEquals(2, data(merged).path("quantity").asInt());
        assertEquals(100, rawCart(BUYER).getList("items", Document.class).size());
        assertEquals(2L, rawCart(BUYER).getLong("version"));
    }

    @Test
    void shouldReplayKeyedPostWithoutCallingProductAgain() throws Exception {
        when(productCatalogClient.validateSku("product-001", "sku-001"))
                .thenReturn(sku("sku-001"));

        String body = """
            {"product_id":"product-001","sku_id":"sku-001","quantity":2}
            """;

        MvcResult first = mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(BUYER))
                        .header("Idempotency-Key", "add-item-001")
                        .header("X-Request-ID", "req-original")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quantity").value(2))
                .andExpect(jsonPath("$.meta.request_id").value("req-original"))
                .andReturn();

        Document before = rawCart(BUYER);

        when(productCatalogClient.validateSku("product-001", "sku-001"))
                .thenThrow(new CartException(
                        CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                        "Product Catalog is unavailable"));

        MvcResult replay = mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(BUYER))
                        .header("Idempotency-Key", "add-item-001")
                        .header("X-Request-ID", "req-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quantity").value(2))
                .andExpect(jsonPath("$.meta.request_id").value("req-original"))
                .andReturn();

        assertEquals(
                JSON.readTree(first.getResponse().getContentAsString()),
                JSON.readTree(replay.getResponse().getContentAsString()));

        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, before.getLong("version"));
        assertEquals(1L, mongoTemplate.getCollection("cart_idempotency_keys").countDocuments());

        verify(productCatalogClient, times(1))
                .validateSku("product-001", "sku-001");
    }

    @Test
    void shouldRetryCasWithoutLosingConcurrentUpdates() throws Exception {
        postItem(BUYER, "sku-001", 1);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger callbacks = new AtomicInteger();

        Callable<Cart> mutation = () -> persistence.mutate(BUYER, snapshot -> {
            Cart current = snapshot.orElseThrow();

            if (callbacks.incrementAndGet() <= 2) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException("Concurrency synchronization failed", ex);
                }
            }

            CartItem old = current.items().getFirst();
            CartItem updated = new CartItem(
                    old.itemId(), old.productId(), old.skuId(), old.shopId(),
                    old.quantity() + 1, old.itemRevision() + 1,
                    old.titleSnapshot(), old.skuLabelSnapshot(),
                    old.unitPriceSnapshot(), old.available(),
                    old.addedAt(), old.updatedAt().plusMillis(1));

            return new Cart(
                    current.id(), current.buyerUserId(),
                    List.of(updated), current.version(),
                    current.createdAt(), current.updatedAt());
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Cart> first = executor.submit(mutation);
            Future<Cart> second = executor.submit(mutation);

            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Cart result = persistence.findByBuyerUserId(BUYER).orElseThrow();

        assertEquals(3, result.items().getFirst().quantity());
        assertEquals(3L, result.items().getFirst().itemRevision());
        assertEquals(3L, result.version());
        assertTrue(callbacks.get() >= 3);
    }

    @Test
    void shouldRejectDifferentBodyWithSameKeyWithoutMutatingCart() throws Exception {
        when(productCatalogClient.validateSku("product-001", "sku-001"))
                .thenReturn(sku("sku-001"));

        mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(BUYER))
                        .header("Idempotency-Key", "add-item-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"product_id":"product-001","sku_id":"sku-001","quantity":2}
                            """))
                .andExpect(status().isCreated());

        Document before = rawCart(BUYER);

        mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(BUYER))
                        .header("Idempotency-Key", "add-item-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"product_id":"product-001","sku_id":"sku-001","quantity":3}
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_IDEMPOTENCY_CONFLICT"));

        assertEquals(before, rawCart(BUYER));
        assertEquals(1L, mongoTemplate.getCollection("cart_idempotency_keys").countDocuments());

        verify(productCatalogClient, times(1))
                .validateSku("product-001", "sku-001");
    }

    private MvcResult postItem(String buyerUserId, String skuId, int quantity) throws Exception {
        when(productCatalogClient.validateSku("product-001", skuId))
                .thenReturn(sku(skuId));

        return mvc.perform(post(CART_PATH + "/items")
                        .with(gateway(buyerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_id":"product-001","sku_id":"%s","quantity":%d}
                                """.formatted(skuId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private ValidatedSkuSnapshot sku(String skuId) {
        return new ValidatedSkuSnapshot(
                "product-001", skuId, "shop-001",
                "Cotton jacket", "Black / L", 249000L);
    }

    private RequestPostProcessor gateway(String buyerUserId) {
        return request -> {
            request.addHeader("X-Gateway-Service-Token", GATEWAY_TOKEN);
            request.addHeader("X-User-ID", buyerUserId);
            return request;
        };
    }

    private JsonNode data(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private Document rawCart(String buyerUserId) {
        Document document = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyerUserId)).first();
        assertNotNull(document);
        return document;
    }

    private Document item(Document cart, int index) {
        return cart.getList("items", Document.class).get(index);
    }

    private long countCarts() {
        return mongoTemplate.getCollection("carts").countDocuments();
    }
}

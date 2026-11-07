package com.taca.cart.api;

import com.taca.cart.application.InventoryStock;
import com.taca.cart.application.ProductHydrationResult;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductHydrationClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
class CartHydrationRegressionIntegrationTest {
    private static final String PATH = "/api/v1/cart";
    private static final String BUYER = "buyer-001";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("product.base-url", () -> "");
        registry.add("inventory.base-url", () -> "");
        registry.add("product.max-concurrency", () -> 10);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CartPersistenceMapper mapper;

    @MockitoBean
    private ProductHydrationClient productClient;

    @MockitoBean
    private InventoryAvailabilityClient inventoryClient;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys").deleteMany(new Document());
    }

    @Test
    void shouldNotLosePutWhenGetIsHydratingOldSnapshot() throws Exception {
        String productId = "product-put";
        String skuId = "sku-put";
        seed(cart(BUYER, List.of(item(productId, skuId))));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(productClient.fetchProduct(productId)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                return ProductHydrationResult.stale();
            }
            return found(productId, skuId, 229000L);
        });

        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<MvcResult> pendingGet = worker.submit(() ->
                    mvc.perform(get(PATH).with(gateway(BUYER))).andReturn());

            assertTrue(entered.await(3, TimeUnit.SECONDS),
                    "GET did not reach Product Hydration");

            mvc.perform(put(PATH + "/items/item-sku-put")
                            .with(gateway(BUYER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":5}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.quantity").value(5));

            Document afterPut = rawCart(BUYER);
            assertEquals(5, storedQuantity(BUYER));
            assertEquals(4L, afterPut.getLong("version"));

            release.countDown();

            JsonNode getData = data(pendingGet.get(8, TimeUnit.SECONDS));
            assertEquals(2, getData.path("items").get(0).path("quantity").asInt());
            assertEquals(1, getData.path("item_count").asInt());
            assertEquals(afterPut, rawCart(BUYER));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void shouldNotResurrectDeletedItemWhenGetIsHydrating() throws Exception {
        String productId = "product-delete";
        String skuId = "sku-delete";
        seed(cart(BUYER, List.of(item(productId, skuId))));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(productClient.fetchProduct(productId)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                return ProductHydrationResult.stale();
            }
            return found(productId, skuId, 229000L);
        });

        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<MvcResult> pendingGet = worker.submit(() ->
                    mvc.perform(get(PATH).with(gateway(BUYER))).andReturn());

            assertTrue(entered.await(3, TimeUnit.SECONDS),
                    "GET did not reach Product Hydration");

            mvc.perform(delete(PATH + "/items/item-sku-delete")
                            .with(gateway(BUYER)))
                    .andExpect(status().isNoContent());

            Document afterDelete = rawCart(BUYER);
            assertTrue(afterDelete.getList("items", Document.class).isEmpty());
            assertEquals(4L, afterDelete.getLong("version"));

            release.countDown();

            JsonNode getData = data(pendingGet.get(8, TimeUnit.SECONDS));
            assertEquals(1, getData.path("item_count").asInt());
            assertEquals("sku-delete",
                    getData.path("items").get(0).path("sku_id").asString());

            assertEquals(afterDelete, rawCart(BUYER));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void shouldCacheProductAcrossGetRequestsButRefreshInventory() throws Exception {
        String productId = "product-cache";
        String skuId = "sku-cache";
        seed(cart(BUYER, List.of(item(productId, skuId))));
        Document before = rawCart(BUYER);

        when(productClient.fetchProduct(productId))
                .thenReturn(found(productId, skuId, 229000L));

        AtomicInteger inventoryReads = new AtomicInteger();

        when(inventoryClient.fetchAvailability(Set.of(skuId))).thenAnswer(invocation -> {
            int call = inventoryReads.incrementAndGet();
            return Map.of(skuId, stock(skuId, call == 1 ? 5 : 0));
        });

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot")
                        .value(229000));

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot")
                        .value(229000));

        verify(productClient, times(1)).fetchProduct(productId);
        verify(inventoryClient, times(2)).fetchAvailability(Set.of(skuId));
        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldRetryProductAfterStaleWithoutCachingFailure() throws Exception {
        String productId = "product-stale-retry";
        String skuId = "sku-stale-retry";
        seed(cart(BUYER, List.of(item(productId, skuId))));

        when(productClient.fetchProduct(productId)).thenReturn(
                ProductHydrationResult.stale(),
                found(productId, skuId, 229000L));

        when(inventoryClient.fetchAvailability(Set.of(skuId)))
                .thenReturn(Map.of(skuId, stock(skuId, 7)));

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("STALE"))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot")
                        .value(249000));

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot")
                        .value(229000));

        verify(productClient, times(2)).fetchProduct(productId);
        verify(inventoryClient, times(1)).fetchAvailability(Set.of(skuId));
    }

    @Test
    void shouldSingleFlightConcurrentHttpGetsForSameProduct() throws Exception {
        String productId = "product-shared";
        String skuId = "sku-shared";
        seed(cart(BUYER, List.of(item(productId, skuId))));
        Document before = rawCart(BUYER);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);

        when(productClient.fetchProduct(productId)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                return ProductHydrationResult.stale();
            }
            return found(productId, skuId, 229000L);
        });

        when(inventoryClient.fetchAvailability(Set.of(skuId)))
                .thenReturn(Map.of(skuId, stock(skuId, 7)));

        ExecutorService workers = Executors.newFixedThreadPool(6);
        List<Future<MvcResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 6; i++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    return mvc.perform(get(PATH).with(gateway(BUYER))).andReturn();
                }));
            }

            start.countDown();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            release.countDown();

            for (Future<MvcResult> future : futures) {
                JsonNode response = data(future.get(8, TimeUnit.SECONDS));
                assertEquals(1, response.path("item_count").asInt());
                assertEquals("sku-shared",
                        response.path("items").get(0).path("sku_id").asString());
            }

            verify(productClient, times(1)).fetchProduct(productId);
            assertEquals(before, rawCart(BUYER));
        } finally {
            start.countDown();
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void shouldHydrateHundredDistinctProductsWithinConcurrencyAndBatchLimits()
            throws Exception {
        List<CartItem> items = IntStream.range(0, 100)
                .mapToObj(i -> {
                    String id = "%03d".formatted(i);
                    return item("product-" + id, "sku-" + id);
                })
                .toList();

        seed(cart(BUYER, items));
        Document before = rawCart(BUYER);

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        when(productClient.fetchProduct(anyString())).thenAnswer(invocation -> {
            String productId = invocation.getArgument(0);
            String suffix = productId.substring("product-".length());

            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);

            try {
                Thread.sleep(8);
                return found(productId, "sku-" + suffix, 1000L);
            } finally {
                active.decrementAndGet();
            }
        });

        when(inventoryClient.fetchAvailability(anySet())).thenAnswer(invocation -> {
            Set<String> skuIds = invocation.getArgument(0);
            return skuIds.stream().collect(Collectors.toMap(
                    skuId -> skuId,
                    skuId -> stock(skuId, 5)));
        });

        JsonNode response = data(
                mvc.perform(get(PATH).with(gateway(BUYER))).andReturn());

        assertEquals(100, response.path("item_count").asInt());
        assertEquals(100, response.path("items").size());

        for (JsonNode item : response.path("items")) {
            assertEquals("AVAILABLE", item.path("availability_status").asString());
            assertEquals(1000L, item.path("unit_price_snapshot").asLong());
            assertEquals(2000L, item.path("line_total").asLong());
        }

        assertTrue(maximum.get() <= 10,
                "Exceeded Product concurrency limit: " + maximum.get());

        verify(productClient, times(100)).fetchProduct(anyString());
        verify(inventoryClient, times(1))
                .fetchAvailability(argThat(skuIds -> skuIds.size() == 100));

        assertEquals(before, rawCart(BUYER));
        assertEquals(100, rawCart(BUYER)
                .getList("items", Document.class).size());
    }

    @Test
    void shouldReturnStaleWhenProductClientThrowsUnexpectedException()
            throws Exception {
        String productId = "product-error";
        String skuId = "sku-error";
        seed(cart(BUYER, List.of(item(productId, skuId))));
        Document before = rawCart(BUYER);

        when(productClient.fetchProduct(productId))
                .thenThrow(new IllegalStateException("Product failure"));

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("STALE"))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot")
                        .value(249000))
                .andExpect(jsonPath("$.data.items[0].available").value(true));

        verifyNoInteractions(inventoryClient);
        assertEquals(before, rawCart(BUYER));
    }

    @Test
    void shouldReturnUnknownWhenInventoryClientFails() throws Exception {
        String productId = "product-inventory-error";
        String skuId = "sku-inventory-error";
        seed(cart(BUYER, List.of(item(productId, skuId))));
        Document before = rawCart(BUYER);

        when(productClient.fetchProduct(productId))
                .thenReturn(found(productId, skuId, 229000L));
        when(inventoryClient.fetchAvailability(Set.of(skuId)))
                .thenThrow(new IllegalStateException("Inventory failure"));

        mvc.perform(get(PATH).with(gateway(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availability_status")
                        .value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot")
                        .value(229000))
                .andExpect(jsonPath("$.data.items[0].available").value(true));

        assertEquals(before, rawCart(BUYER));
    }


    @Test
    void shouldReturnPartialCartWhenOneProductReadExceedsDeadline() throws Exception {
        seed(cart(BUYER, List.of(
                item("product-fast", "sku-fast"),
                item("product-slow", "sku-slow"))));
        Document before = rawCart(BUYER);

        when(productClient.fetchProduct("product-fast"))
                .thenReturn(found("product-fast", "sku-fast", 229000L));

        when(productClient.fetchProduct("product-slow")).thenAnswer(invocation -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ProductHydrationResult.stale();
        });

        when(inventoryClient.fetchAvailability(Set.of("sku-fast")))
                .thenReturn(Map.of("sku-fast", stock("sku-fast", 7)));

        long started = System.nanoTime();
        JsonNode response = data(
                mvc.perform(get(PATH).with(gateway(BUYER))).andReturn());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertEquals(2, response.path("item_count").asInt());
        assertEquals("AVAILABLE",
                response.path("items").get(0).path("availability_status").asString());
        assertEquals("STALE",
                response.path("items").get(1).path("availability_status").asString());
        assertEquals(229000L,
                response.path("items").get(0).path("unit_price_snapshot").asLong());
        assertEquals(249000L,
                response.path("items").get(1).path("unit_price_snapshot").asLong());

        assertTrue(elapsedMs < 6000,
                "Hydration waited too long for Product: " + elapsedMs + " ms");
        verify(inventoryClient).fetchAvailability(Set.of("sku-fast"));
        assertEquals(before, rawCart(BUYER));
    }


    private RequestPostProcessor gateway(String buyer) {
        return request -> {
            request.addHeader("X-Gateway-Service-Token", GATEWAY_TOKEN);
            request.addHeader("X-User-ID", buyer);
            return request;
        };
    }

    private void seed(Cart cart) {
        mongoTemplate.insert(mapper.toDocument(cart));
    }

    private Cart cart(String buyer, List<CartItem> items) {
        return new Cart("cart-" + buyer, buyer, items, 3L, NOW, NOW);
    }

    private CartItem item(String productId, String skuId) {
        return new CartItem(
                "item-" + skuId, productId, skuId, "shop-001",
                2, 2L, "Old title", "Old label",
                249000L, true, NOW, NOW);
    }

    private ProductHydrationResult found(
            String productId, String skuId, long price) {
        return ProductHydrationResult.found(Map.of(
                skuId, new ValidatedSkuSnapshot(
                        productId, skuId, "shop-001",
                        "Current title", "Black / L", price)));
    }

    private InventoryStock stock(String skuId, long quantity) {
        return new InventoryStock(skuId, quantity, "ACTIVE", NOW);
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return JSON.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private Document rawCart(String buyer) {
        Document document = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer)).first();
        assertNotNull(document);
        return document;
    }

    private int storedQuantity(String buyer) {
        return rawCart(buyer).getList("items", Document.class)
                .getFirst().getInteger("quantity");
    }
}

package com.taca.cart.api;

import com.mongodb.client.result.UpdateResult;
import com.taca.cart.api.dto.CheckedOutCartRequest;
import com.taca.cart.api.dto.CheckedOutCartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.api.validation.CheckedOutCartRequestValidator;
import com.taca.cart.application.CartMutationService;
import com.taca.cart.application.InternalCartCleanupService;
import com.taca.cart.application.ValidatedSkuSnapshot;
import com.taca.cart.config.CartProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.integrations.ProductCatalogClient;
import com.taca.cart.integrations.ProductHydrationClient;
import com.taca.cart.persistence.CartCasPersistence;
import com.taca.cart.persistence.document.CartDocument;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "cart.indexes.initialize=false"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InternalCartCheckoutConcurrencyRegressionTest {
    private static final String BUYER = "buyer-001";
    private static final String CART_ID = "cart-buyer-001";
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
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
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CartPersistenceMapper mapper;

    @Autowired
    private CartProperties properties;

    @Autowired
    private CheckedOutCartRequestValidator validator;

    @Autowired
    private CartMutationService buyerMutation;

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
    }

    @Test
    void shouldPreserveBuyerPutWhenItWinsBeforeCleanupCas() {
        seed(cart(defaultItems()));

        InternalCartCleanupService cleanup = cleanupWithBeforeFirstCas(
                () -> buyerMutation.updateQuantity(BUYER, "item-001", 7));

        CheckedOutCartResponse response = cleanup.cleanup(BUYER,
                request("group-001", List.of("item-001"),
                        Map.of("item-001", 3L)));

        assertTrue(response.removedItemIds().isEmpty());
        assertTrue(response.missingItemIds().isEmpty());
        assertEquals(List.of("item-001"), response.skippedModifiedItemIds());
        assertEquals(3, response.remainingItemCount());

        Document item = storedItem(BUYER, "item-001");
        assertEquals(7, item.getInteger("quantity"));
        assertEquals(4L, item.getLong("item_revision"));
        assertEquals(6L, rawCart(BUYER).getLong("version"));
        assertNoExternalCalls();
    }

    @Test
    void shouldNotResurrectBuyerDeletedItemAfterCasRetry() {
        seed(cart(defaultItems()));

        InternalCartCleanupService cleanup = cleanupWithBeforeFirstCas(
                () -> buyerMutation.removeItem(BUYER, "item-001"));

        CheckedOutCartResponse response = cleanup.cleanup(BUYER,
                request("group-001", List.of("item-001"),
                        Map.of("item-001", 3L)));

        assertTrue(response.removedItemIds().isEmpty());
        assertEquals(List.of("item-001"), response.missingItemIds());
        assertTrue(response.skippedModifiedItemIds().isEmpty());
        assertEquals(2, response.remainingItemCount());

        assertNull(findItem(BUYER, "item-001"));
        assertEquals(6L, rawCart(BUYER).getLong("version"));
        assertNoExternalCalls();
    }

    @Test
    void shouldPreserveNewlyAddedSkuWhenCleanupCasRetries() {
        seed(cart(defaultItems()));

        InternalCartCleanupService cleanup = cleanupWithBeforeFirstCas(
                () -> buyerMutation.addItem(BUYER, sku("004"), 2));

        CheckedOutCartResponse response = cleanup.cleanup(BUYER,
                request("group-001", List.of("item-001"),
                        Map.of("item-001", 3L)));

        assertEquals(List.of("item-001"), response.removedItemIds());
        assertTrue(response.missingItemIds().isEmpty());
        assertTrue(response.skippedModifiedItemIds().isEmpty());
        assertEquals(3, response.remainingItemCount());

        assertNull(findItem(BUYER, "item-001"));
        assertNotNull(findItemBySku(BUYER, "sku-004"));
        assertEquals(2, findItemBySku(BUYER, "sku-004")
                .getInteger("quantity"));
        assertEquals(7L, rawCart(BUYER).getLong("version"));
        assertNoExternalCalls();
    }

    @Test
    void shouldPreserveUnrelatedBuyerPutDuringCleanupRetry() {
        seed(cart(defaultItems()));

        InternalCartCleanupService cleanup = cleanupWithBeforeFirstCas(
                () -> buyerMutation.updateQuantity(BUYER, "item-002", 9));

        CheckedOutCartResponse response = cleanup.cleanup(BUYER,
                request("group-001", List.of("item-001"),
                        Map.of("item-001", 3L)));

        assertEquals(List.of("item-001"), response.removedItemIds());
        assertEquals(2, response.remainingItemCount());

        assertNull(findItem(BUYER, "item-001"));
        assertEquals(9, storedItem(BUYER, "item-002")
                .getInteger("quantity"));
        assertEquals(3L, storedItem(BUYER, "item-002")
                .getLong("item_revision"));
        assertEquals(7L, rawCart(BUYER).getLong("version"));
        assertNoExternalCalls();
    }

    @Test
    void shouldReturnConflictAfterCasRetryBudgetIsExhausted() {
        seed(cart(defaultItems()));
        Document before = rawCart(BUYER);

        MongoOperations hookedMongo = spy((MongoOperations) mongoTemplate);
        AtomicInteger attempts = new AtomicInteger();

        doAnswer(invocation -> {
            attempts.incrementAndGet();
            return UpdateResult.acknowledged(0L, 0L, null);
        }).when(hookedMongo).updateFirst(
                any(Query.class),
                any(UpdateDefinition.class),
                eq(CartDocument.class));

        InternalCartCleanupService cleanup = new InternalCartCleanupService(
                new CartCasPersistence(hookedMongo, mapper, properties),
                validator);

        CartException ex = assertThrows(CartException.class,
                () -> cleanup.cleanup(BUYER,
                        request("group-001", List.of("item-001"),
                                Map.of("item-001", 3L))));

        assertEquals(CartErrorCode.CART_VERSION_CONFLICT, ex.getCode());
        assertEquals(properties.writeRetryMax() + 1, attempts.get());
        assertEquals(before, rawCart(BUYER));
        assertNoExternalCalls();
    }

    @Test
    void shouldBeSafeForG1ThenG2ThenReplayG1() throws Exception {
        seed(cart(defaultItems()));

        MvcResult g1 = postCheckedOut(BUYER, "group-001",
                List.of("item-001"), Map.of("item-001", 3L));

        MvcResult g2 = postCheckedOut(BUYER, "group-002",
                List.of("item-002"), Map.of("item-002", 2L));

        Document beforeReplay = rawCart(BUYER);

        MvcResult replay = postCheckedOut(BUYER, "group-001",
                List.of("item-001"), Map.of("item-001", 3L));

        assertStatus(g1, 200);
        assertStatus(g2, 200);
        assertStatus(replay, 200);

        assertEquals("item-001",
                data(g1).path("removed_item_ids").get(0).asString());
        assertEquals("item-002",
                data(g2).path("removed_item_ids").get(0).asString());

        assertEquals(0, data(replay).path("removed_item_ids").size());
        assertEquals("item-001",
                data(replay).path("missing_item_ids").get(0).asString());
        assertEquals(1, data(replay).path("remaining_item_count").asInt());

        assertEquals(beforeReplay, rawCart(BUYER));
        assertEquals(7L, rawCart(BUYER).getLong("version"));
        assertNotNull(findItem(BUYER, "item-003"));
        assertNoExternalCalls();
    }

    @Test
    void shouldNotDeleteReaddedSkuUsingOldCleanupPayload()
            throws Exception {
        seed(cart(defaultItems()));

        MvcResult first = postCheckedOut(BUYER, "group-001",
                List.of("item-001"), Map.of("item-001", 3L));

        assertStatus(first, 200);

        Cart afterReadd = buyerMutation.addItem(BUYER, sku("001"), 1);

        CartItem newItem = afterReadd.items().stream()
                .filter(item -> item.skuId().equals("sku-001"))
                .findFirst().orElseThrow();

        assertNotEquals("item-001", newItem.itemId());
        assertEquals(1L, newItem.itemRevision());

        Document beforeReplay = rawCart(BUYER);

        MvcResult replay = postCheckedOut(BUYER, "group-001",
                List.of("item-001"), Map.of("item-001", 3L));

        assertStatus(replay, 200);
        assertEquals(0, data(replay).path("removed_item_ids").size());
        assertEquals("item-001",
                data(replay).path("missing_item_ids").get(0).asString());

        assertEquals(beforeReplay, rawCart(BUYER));
        assertNotNull(findItem(BUYER, newItem.itemId()));
        assertEquals(7L, rawCart(BUYER).getLong("version"));
        assertNoExternalCalls();
    }

    @Test
    void shouldAllowOnlyOneMutationForConcurrentSamePayload()
            throws Exception {
        seed(cart(defaultItems()));

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> postCheckedOut(BUYER, "group-001",
                    List.of("item-001"), Map.of("item-001", 3L)));
        }

        List<MvcResult> results = runConcurrent(tasks);
        int removedCount = 0;
        int missingCount = 0;

        for (MvcResult result : results) {
            assertStatus(result, 200);
            JsonNode response = data(result);

            if (response.path("removed_item_ids").size() == 1) {
                removedCount++;
                assertEquals("item-001",
                        response.path("removed_item_ids").get(0).asString());
            } else {
                assertEquals(0, response.path("removed_item_ids").size());
                assertEquals("item-001",
                        response.path("missing_item_ids").get(0).asString());
                missingCount++;
            }
        }

        assertEquals(1, removedCount);
        assertEquals(3, missingCount);
        assertEquals(6L, rawCart(BUYER).getLong("version"));
        assertNull(findItem(BUYER, "item-001"));
        assertEquals(2, storedItems(BUYER).size());
        assertNoExternalCalls();
    }

    @Test
    void shouldNotLoseUpdatesFromConcurrentDisjointCheckouts()
            throws Exception {
        seed(cart(defaultItems()));

        List<MvcResult> results = runConcurrent(List.of(
                () -> postCheckedOut(BUYER, "group-001",
                        List.of("item-001"), Map.of("item-001", 3L)),
                () -> postCheckedOut(BUYER, "group-002",
                        List.of("item-002"), Map.of("item-002", 2L))
        ));

        for (MvcResult result : results) {
            assertStatus(result, 200);
            assertEquals(1, data(result).path("removed_item_ids").size());
        }

        assertNull(findItem(BUYER, "item-001"));
        assertNull(findItem(BUYER, "item-002"));
        assertNotNull(findItem(BUYER, "item-003"));
        assertEquals(1, storedItems(BUYER).size());
        assertEquals(7L, rawCart(BUYER).getLong("version"));
        assertNoExternalCalls();
    }

    @Test
    void shouldNotLoseSuccessfulPutDuringConcurrentCleanup()
            throws Exception {
        seed(cart(defaultItems()));

        List<MvcResult> results = runConcurrent(List.of(
                () -> postCheckedOut(BUYER, "group-001",
                        List.of("item-001"), Map.of("item-001", 3L)),
                () -> buyerPut("item-001", 7)
        ));

        MvcResult cleanupResult = results.get(0);
        MvcResult putResult = results.get(1);

        assertStatus(cleanupResult, 200);

        int putStatus = putResult.getResponse().getStatus();
        assertTrue(putStatus == 200 || putStatus == 404,
                "Unexpected PUT status: " + putStatus);

        if (putStatus == 200) {
            Document updated = storedItem(BUYER, "item-001");

            assertEquals(7, updated.getInteger("quantity"));
            assertEquals(4L, updated.getLong("item_revision"));
            assertEquals(0,
                    data(cleanupResult).path("removed_item_ids").size());
            assertEquals("item-001",
                    data(cleanupResult).path("skipped_modified_item_ids")
                            .get(0).asString());
        } else {
            assertNull(findItem(BUYER, "item-001"));
            assertEquals("item-001",
                    data(cleanupResult).path("removed_item_ids")
                            .get(0).asString());
        }

        assertNotNull(findItem(BUYER, "item-002"));
        assertNotNull(findItem(BUYER, "item-003"));
        assertNoExternalCalls();
    }

    @Test
    void shouldNotResurrectItemDuringConcurrentBuyerDelete()
            throws Exception {
        seed(cart(defaultItems()));

        List<MvcResult> results = runConcurrent(List.of(
                () -> postCheckedOut(BUYER, "group-001",
                        List.of("item-001"), Map.of("item-001", 3L)),
                () -> buyerDelete("item-001")
        ));

        MvcResult cleanupResult = results.get(0);
        MvcResult deleteResult = results.get(1);

        assertStatus(cleanupResult, 200);

        int deleteStatus = deleteResult.getResponse().getStatus();
        assertTrue(deleteStatus == 204 || deleteStatus == 404,
                "Unexpected DELETE status: " + deleteStatus);

        if (deleteStatus == 204) {
            assertEquals("item-001",
                    data(cleanupResult).path("missing_item_ids")
                            .get(0).asString());
        } else {
            assertEquals("item-001",
                    data(cleanupResult).path("removed_item_ids")
                            .get(0).asString());
        }

        assertNull(findItem(BUYER, "item-001"));
        assertEquals(6L, rawCart(BUYER).getLong("version"));
        assertEquals(2, storedItems(BUYER).size());
        assertNoExternalCalls();
    }

    @Test
    void shouldHandleDifferentCheckoutGroupsForSameItemConcurrently()
            throws Exception {
        seed(cart(defaultItems()));

        List<MvcResult> results = runConcurrent(List.of(
                () -> postCheckedOut(BUYER, "group-001",
                        List.of("item-001"), Map.of("item-001", 3L)),
                () -> postCheckedOut(BUYER, "group-002",
                        List.of("item-001"), Map.of("item-001", 3L))
        ));

        int removed = 0;
        int missing = 0;

        for (MvcResult result : results) {
            assertStatus(result, 200);
            JsonNode response = data(result);
            removed += response.path("removed_item_ids").size();
            missing += response.path("missing_item_ids").size();
        }

        assertEquals(1, removed);
        assertEquals(1, missing);
        assertNull(findItem(BUYER, "item-001"));
        assertEquals(6L, rawCart(BUYER).getLong("version"));
        assertNoExternalCalls();
    }

    private InternalCartCleanupService cleanupWithBeforeFirstCas(
            Runnable beforeFirstWrite) {
        MongoOperations hookedMongo = spy((MongoOperations) mongoTemplate);
        AtomicBoolean injected = new AtomicBoolean();

        doAnswer(invocation -> {
            if (injected.compareAndSet(false, true)) {
                beforeFirstWrite.run();
            }
            return invocation.callRealMethod();
        }).when(hookedMongo).updateFirst(
                any(Query.class),
                any(UpdateDefinition.class),
                eq(CartDocument.class));

        return new InternalCartCleanupService(
                new CartCasPersistence(hookedMongo, mapper, properties),
                validator);
    }

    private List<MvcResult> runConcurrent(
            List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();

        try {
            for (Callable<MvcResult> task : tasks) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Concurrent start deadline exceeded");
                    }
                    return task.call();
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Workers did not become ready");
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private MvcResult postCheckedOut(
            String buyer,
            String groupId,
            List<String> itemIds,
            Map<String, Long> revisions) throws Exception {
        CheckedOutCartRequest body = new CheckedOutCartRequest(
                "cart-" + buyer, groupId, itemIds, revisions);

        return mvc.perform(post("/api/v1/internal/carts/" + buyer
                        + "/checked-out")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult buyerPut(String itemId, int quantity)
            throws Exception {
        return mvc.perform(put("/api/v1/cart/items/" + itemId)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":" + quantity + "}"))
                .andReturn();
    }

    private MvcResult buyerDelete(String itemId) throws Exception {
        return mvc.perform(delete("/api/v1/cart/items/" + itemId)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER))
                .andReturn();
    }

    private CheckedOutCartRequest request(
            String groupId,
            List<String> itemIds,
            Map<String, Long> revisions) {
        return new CheckedOutCartRequest(
                CART_ID, groupId, itemIds, revisions);
    }

    private ValidatedSkuSnapshot sku(String suffix) {
        return new ValidatedSkuSnapshot(
                "product-" + suffix,
                "sku-" + suffix,
                "shop-001",
                "New product",
                "Default",
                199000L);
    }

    private void seed(Cart cart) {
        mongoTemplate.insert(mapper.toDocument(cart));
    }

    private Cart cart(List<CartItem> items) {
        return new Cart(CART_ID, BUYER, items, 5L, NOW, NOW);
    }

    private List<CartItem> defaultItems() {
        return List.of(
                item("001", 2, 3L),
                item("002", 5, 2L),
                item("003", 1, 1L));
    }

    private CartItem item(String suffix, int quantity, long revision) {
        return new CartItem(
                "item-" + suffix,
                "product-" + suffix,
                "sku-" + suffix,
                "shop-001",
                quantity,
                revision,
                "Stored title",
                "Stored label",
                249000L,
                true,
                NOW,
                NOW);
    }

    private Document rawCart(String buyer) {
        Document document = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", buyer)).first();
        assertNotNull(document);
        return document;
    }

    private List<Document> storedItems(String buyer) {
        return rawCart(buyer).getList("items", Document.class);
    }

    private Document findItem(String buyer, String itemId) {
        return storedItems(buyer).stream()
                .filter(item -> itemId.equals(item.getString("item_id")))
                .findFirst().orElse(null);
    }

    private Document storedItem(String buyer, String itemId) {
        Document item = findItem(buyer, itemId);
        assertNotNull(item, "Missing item: " + itemId);
        return item;
    }

    private Document findItemBySku(String buyer, String skuId) {
        return storedItems(buyer).stream()
                .filter(item -> skuId.equals(item.getString("sku_id")))
                .findFirst().orElse(null);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return JSON.readTree(
                result.getResponse().getContentAsString()).path("data");
    }

    private void assertStatus(MvcResult result, int expected) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private void assertNoExternalCalls() {
        verifyNoInteractions(
                productCatalogClient,
                productHydrationClient,
                inventoryClient);
    }
}

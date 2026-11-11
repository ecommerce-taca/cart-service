package com.taca.cart.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "cart.indexes.initialize=true",
                "cart.hydrate-timeout=3s",
                "product.timeout=800ms",
                "inventory.timeout=400ms"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartFailureConcurrencyE2EIntegrationTest {
    private static final String CART_PATH = "/api/v1/cart";
    private static final String INTERNAL_PATH = "/api/v1/internal/carts";
    private static final String BUYER = "buyer-phase9f";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String INVENTORY_TOKEN = "c".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    private static HttpServer productServer;
    private static HttpServer inventoryServer;
    private static ExecutorService providerThreads;

    private static final AtomicBoolean PRODUCT_FAILURE = new AtomicBoolean();
    private static final AtomicBoolean PRODUCT_SLOW = new AtomicBoolean();
    private static final AtomicBoolean INVENTORY_SLOW = new AtomicBoolean();
    private static final AtomicInteger PRODUCT_CALLS = new AtomicInteger();
    private static final AtomicInteger INVENTORY_CALLS = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        startProviders();
        registry.add("spring.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("cartdb_test"));
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("product.base-url", () -> baseUrl(productServer));
        registry.add("inventory.base-url", () -> baseUrl(inventoryServer));
        registry.add("inventory.internal-token", () -> INVENTORY_TOKEN);
    }

    private static synchronized void startProviders() {
        if (productServer != null && inventoryServer != null) return;

        try {
            productServer = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);
            inventoryServer = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);

            providerThreads = Executors.newCachedThreadPool();
            productServer.setExecutor(providerThreads);
            inventoryServer.setExecutor(providerThreads);

            productServer.createContext(
                    "/api/v1/products/",
                    CartFailureConcurrencyE2EIntegrationTest::handleProduct);
            inventoryServer.createContext(
                    "/internal/inventory/availability",
                    CartFailureConcurrencyE2EIntegrationTest::handleInventory);

            productServer.start();
            inventoryServer.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start Phase 9F providers", ex);
        }
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void handleProduct(HttpExchange exchange) throws IOException {
        PRODUCT_CALLS.incrementAndGet();

        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/v1/products/";

        if (!"GET".equals(exchange.getRequestMethod())
                || !path.startsWith(prefix)) {
            respond(exchange, 404, "{}");
            return;
        }

        if (PRODUCT_SLOW.get()) sleep(1800);

        if (PRODUCT_FAILURE.get()) {
            respond(exchange, 503, "{}");
            return;
        }

        String productId = path.substring(prefix.length());

        if (!productId.startsWith("product-")) {
            respond(exchange, 404, "{}");
            return;
        }

        String skuId = "sku-" + productId.substring("product-".length());

        String body = """
                {
                  "data": {
                    "product_id": "%s",
                    "status": "ACTIVE",
                    "title": "Test product",
                    "shop": {"shop_id": "shop-001"},
                    "skus": [{
                      "sku_id": "%s",
                      "status": "ACTIVE",
                      "attributes": {"size": "M"},
                      "price": {
                        "sale_price": 249000,
                        "currency": "VND"
                      }
                    }]
                  }
                }
                """.formatted(productId, skuId);

        respond(exchange, 200, body);
    }

    private static void handleInventory(HttpExchange exchange) throws IOException {
        INVENTORY_CALLS.incrementAndGet();

        String token = exchange.getRequestHeaders()
                .getFirst("X-Internal-Service-Token");

        if (!INVENTORY_TOKEN.equals(token)) {
            respond(exchange, 401, "{}");
            return;
        }

        String raw = exchange.getRequestURI().getRawQuery();
        String query = raw == null ? ""
                : URLDecoder.decode(raw, StandardCharsets.UTF_8);

        if (!"GET".equals(exchange.getRequestMethod())
                || !query.startsWith("sku_ids=")) {
            respond(exchange, 400, "{}");
            return;
        }

        String csv = query.substring("sku_ids=".length());

        if (INVENTORY_SLOW.get()) sleep(1600);

        String data = Arrays.stream(csv.split(","))
                .map(skuId -> """
                        {
                          "sku_id": "%s",
                          "available_qty": 8,
                          "status": "ACTIVE",
                          "as_of": "2026-11-11T00:00:00Z"
                        }
                        """.formatted(skuId))
                .collect(Collectors.joining(","));

        respond(exchange, 200, "{\"data\":[" + data + "]}");
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);

        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    static void stopProviders() {
        if (productServer != null) productServer.stop(0);
        if (inventoryServer != null) inventoryServer.stop(0);
        if (providerThreads != null) providerThreads.shutdownNow();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void reset() {
        mongoTemplate.getCollection("carts").deleteMany(new Document());
        mongoTemplate.getCollection("cart_idempotency_keys")
                .deleteMany(new Document());

        PRODUCT_FAILURE.set(false);
        PRODUCT_SLOW.set(false);
        INVENTORY_SLOW.set(false);
        PRODUCT_CALLS.set(0);
        INVENTORY_CALLS.set(0);
    }

    @Test
    void shouldNotCreateCartWhenProductFailsAndRecoverAfterwards()
            throws Exception {
        PRODUCT_FAILURE.set(true);

        MvcResult failed = add("product-failure", "sku-failure", 2, null);

        assertEquals(503, failed.getResponse().getStatus());
        assertEquals("CART_DEPENDENCY_UNAVAILABLE", errorCode(failed));
        assertEquals(0L, cartCount());

        PRODUCT_FAILURE.set(false);

        MvcResult recovered = add("product-failure", "sku-failure", 2, null);

        assertEquals(201, recovered.getResponse().getStatus());
        assertEquals(2, data(recovered).path("quantity").asInt());
        assertEquals(1L, cartCount());
        assertEquals(1, storedItems().size());
    }

    @Test
    void shouldReturnStaleWithoutMutatingCartOnProductTimeout()
            throws Exception {
        MvcResult added = add("product-slow", "sku-slow", 2, null);
        assertEquals(201, added.getResponse().getStatus());

        Document before = rawCart();
        assertNotNull(before);

        PRODUCT_SLOW.set(true);

        MvcResult result = buyerGet();
        JsonNode item = data(result).path("items").get(0);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("STALE", item.path("availability_status").asText());
        assertEquals(2, item.path("quantity").asInt());
        assertEquals(249000L, item.path("unit_price_snapshot").asLong());
        assertEquals(before, rawCart());
        assertEquals(0, INVENTORY_CALLS.get());
    }

    @Test
    void shouldRecoverInventoryAfterTimeoutWithoutChangingPersistence()
            throws Exception {
        MvcResult added = add("product-stock", "sku-stock", 2, null);
        assertEquals(201, added.getResponse().getStatus());

        Document before = rawCart();
        INVENTORY_SLOW.set(true);

        MvcResult degraded = buyerGet();
        JsonNode degradedItem = data(degraded).path("items").get(0);

        assertEquals(200, degraded.getResponse().getStatus());
        assertEquals("UNKNOWN",
                degradedItem.path("availability_status").asText());
        assertEquals(before, rawCart());

        INVENTORY_SLOW.set(false);

        MvcResult recovered = buyerGet();
        JsonNode recoveredItem = data(recovered).path("items").get(0);

        assertEquals(200, recovered.getResponse().getStatus());
        assertEquals("AVAILABLE",
                recoveredItem.path("availability_status").asText());
        assertTrue(recoveredItem.path("available").asBoolean());
        assertEquals(before, rawCart());
        assertTrue(INVENTORY_CALLS.get() >= 2);
    }

    @Test
    void shouldReplayIdempotentAddWithoutCallingFailedProductAgain()
            throws Exception {
        String key = "phase9f-idempotency-001";

        MvcResult first = add("product-replay", "sku-replay", 2, key);

        assertEquals(201, first.getResponse().getStatus());
        String itemId = data(first).path("item_id").asText();
        int callsBeforeReplay = PRODUCT_CALLS.get();

        PRODUCT_FAILURE.set(true);

        MvcResult replay = add("product-replay", "sku-replay", 2, key);

        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(data(first), data(replay));
        assertEquals(itemId, data(replay).path("item_id").asText());
        assertEquals(callsBeforeReplay, PRODUCT_CALLS.get());

        assertEquals(1L, cartCount());
        assertEquals(1, storedItems().size());
        assertEquals(2, storedItems().get(0).getInteger("quantity"));
        assertEquals(1L, mongoTemplate.getCollection(
                "cart_idempotency_keys").countDocuments());
    }

    @Test
    void shouldSkipCleanupWhenBuyerChangedRevision()
            throws Exception {
        MvcResult added = add("product-revision", "sku-revision", 2, null);

        assertEquals(201, added.getResponse().getStatus());

        String cartId = data(added).path("cart_id").asText();
        String itemId = data(added).path("item_id").asText();

        JsonNode checkoutItem = data(internalGet(itemId))
                .path("items").get(0);

        long oldRevision = checkoutItem.path("item_revision").asLong();
        assertEquals(1L, oldRevision);

        MvcResult updated = buyerPut(itemId, 5);

        assertEquals(200, updated.getResponse().getStatus());

        Document afterUpdate = rawCart();
        assertNotNull(afterUpdate);

        MvcResult staleCleanup = checkedOut(
                cartId, itemId, oldRevision, "group-stale");

        assertEquals(200, staleCleanup.getResponse().getStatus());

        JsonNode staleData = data(staleCleanup);
        assertEquals(0, staleData.path("removed_item_ids").size());
        assertEquals(itemId,
                staleData.path("skipped_modified_item_ids").get(0).asText());

        assertEquals(afterUpdate, rawCart());
        assertEquals(5, storedItems().get(0).getInteger("quantity"));
        assertEquals(2L, storedItems().get(0).getLong("item_revision"));

        MvcResult validCleanup = checkedOut(
                cartId, itemId, 2L, "group-current");

        assertEquals(200, validCleanup.getResponse().getStatus());
        assertEquals(itemId, data(validCleanup)
                .path("removed_item_ids").get(0).asText());
        assertEquals(0, storedItems().size());
        assertEquals(1L, cartCount());
    }

    @Test
    void shouldPreserveSuccessfulUpdateDuringConcurrentCleanup()
            throws Exception {
        MvcResult added = add("product-race", "sku-race", 2, null);

        assertEquals(201, added.getResponse().getStatus());

        String cartId = data(added).path("cart_id").asText();
        String itemId = data(added).path("item_id").asText();

        long revision = data(internalGet(itemId))
                .path("items").get(0).path("item_revision").asLong();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<MvcResult> updateFuture = workers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("PUT did not start");
                }
                return buyerPut(itemId, 7);
            });

            Future<MvcResult> cleanupFuture = workers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Cleanup did not start");
                }
                return checkedOut(cartId, itemId, revision, "group-race");
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            MvcResult update = updateFuture.get(30, TimeUnit.SECONDS);
            MvcResult cleanup = cleanupFuture.get(30, TimeUnit.SECONDS);

            int putStatus = update.getResponse().getStatus();
            int cleanupStatus = cleanup.getResponse().getStatus();

            assertTrue(putStatus == 200 || putStatus == 404 || putStatus == 409,
                    "Unexpected PUT status: " + putStatus);
            assertTrue(cleanupStatus == 200 || cleanupStatus == 409,
                    "Unexpected cleanup status: " + cleanupStatus);

            if (putStatus == 200) {
                Document item = storedItem(itemId);
                assertNotNull(item);
                assertEquals(7, item.getInteger("quantity"));
                assertEquals(2L, item.getLong("item_revision"));

                if (cleanupStatus == 200) {
                    JsonNode result = data(cleanup);
                    assertEquals(0, result.path("removed_item_ids").size());
                    assertEquals(itemId,
                            result.path("skipped_modified_item_ids")
                                    .get(0).asText());
                } else {
                    assertEquals("CART_VERSION_CONFLICT", errorCode(cleanup));
                }
            } else {
                assertEquals(200, cleanupStatus);
                assertNull(storedItem(itemId));
                assertEquals(itemId,
                        data(cleanup).path("removed_item_ids")
                                .get(0).asText());

                if (putStatus == 409) {
                    assertEquals("CART_VERSION_CONFLICT", errorCode(update));
                }
            }

            assertEquals(2L, rawCart().getLong("version"));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private MvcResult add(String productId, String skuId,
                          int quantity, String key) throws Exception {
        String body = """
                {
                  "product_id": "%s",
                  "sku_id": "%s",
                  "quantity": %d
                }
                """.formatted(productId, skuId, quantity);

        var request = post(CART_PATH + "/items")
                .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                .header("X-User-ID", BUYER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);

        if (key != null) request.header("Idempotency-Key", key);

        return mvc.perform(request).andReturn();
    }

    private MvcResult buyerGet() throws Exception {
        return mvc.perform(get(CART_PATH)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER))
                .andReturn();
    }

    private MvcResult buyerPut(String itemId, int quantity) throws Exception {
        return mvc.perform(put(CART_PATH + "/items/" + itemId)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":" + quantity + "}"))
                .andReturn();
    }

    private MvcResult internalGet(String itemId) throws Exception {
        return mvc.perform(get(INTERNAL_PATH + "/" + BUYER)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("item_ids", itemId))
                .andReturn();
    }

    private MvcResult checkedOut(String cartId, String itemId,
                                 long revision, String group) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "cart_id", cartId,
                "checkout_group_id", group,
                "item_ids", List.of(itemId),
                "expected_item_revisions", Map.of(itemId, revision)
        ));

        return mvc.perform(post(INTERNAL_PATH + "/" + BUYER + "/checked-out")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString())
                .path("data");
    }

    private String errorCode(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString())
                .path("error").path("code").asText();
    }

    private Document rawCart() {
        return mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", BUYER))
                .first();
    }

    private List<Document> storedItems() {
        Document cart = rawCart();
        assertNotNull(cart);
        return cart.getList("items", Document.class);
    }

    private Document storedItem(String itemId) {
        return storedItems().stream()
                .filter(item -> itemId.equals(item.getString("item_id")))
                .findFirst().orElse(null);
    }

    private long cartCount() {
        return mongoTemplate.getCollection("carts").countDocuments();
    }
}

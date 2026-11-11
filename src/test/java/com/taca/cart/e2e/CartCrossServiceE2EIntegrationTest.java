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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "cart.indexes.initialize=false",
                "cart.hydrate-timeout=4s",
                "product.timeout=2s",
                "inventory.timeout=1s"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartCrossServiceE2EIntegrationTest {
    private static final String CART_PATH = "/api/v1/cart";
    private static final String INTERNAL_PATH = "/api/v1/internal/carts";
    private static final String BUYER = "buyer-001";
    private static final String BUYER_2 = "buyer-002";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String INVENTORY_TOKEN = "c".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PRODUCT_BODY = """
            {
              "data": {
                "product_id": "product-001",
                "status": "ACTIVE",
                "title": "Cotton jacket",
                "shop": {"shop_id": "shop-001"},
                "skus": [
                  {
                    "sku_id": "sku-001",
                    "status": "ACTIVE",
                    "attributes": {"color": "black", "size": "L"},
                    "price": {"sale_price": 249000, "currency": "VND"}
                  },
                  {
                    "sku_id": "sku-002",
                    "status": "ACTIVE",
                    "attributes": {"color": "white", "size": "M"},
                    "price": {"sale_price": 199000, "currency": "VND"}
                  }
                ]
              },
              "meta": {"request_id": "req-product-e2e"}
            }
            """;

    private static final String STOCK_001 = """
            {
              "sku_id": "sku-001",
              "available_qty": 7,
              "status": "ACTIVE",
              "as_of": "2026-11-11T00:00:00Z"
            }
            """;

    private static final String STOCK_002 = """
            {
              "sku_id": "sku-002",
              "available_qty": 5,
              "status": "ACTIVE",
              "as_of": "2026-11-11T00:00:00Z"
            }
            """;

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withReplicaSet();

    private static HttpServer productServer;
    private static HttpServer inventoryServer;
    private static final AtomicInteger PRODUCT_CALLS = new AtomicInteger();
    private static final Queue<String> INVENTORY_QUERIES =
            new ConcurrentLinkedQueue<>();
    private static final AtomicReference<String> INVENTORY_TOKEN_RECEIVED =
            new AtomicReference<>();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
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

            productServer.createContext(
                    "/api/v1/products/", CartCrossServiceE2EIntegrationTest::handleProduct);
            inventoryServer.createContext(
                    "/internal/inventory/availability",
                    CartCrossServiceE2EIntegrationTest::handleInventory);

            productServer.start();
            inventoryServer.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start fake HTTP providers", ex);
        }
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void handleProduct(HttpExchange exchange) throws IOException {
        PRODUCT_CALLS.incrementAndGet();

        if (!"GET".equals(exchange.getRequestMethod())
                || !"/api/v1/products/product-001".equals(
                exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "{}");
            return;
        }

        respond(exchange, 200, PRODUCT_BODY);
    }

    private static void handleInventory(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestHeaders()
                .getFirst("X-Internal-Service-Token");
        INVENTORY_TOKEN_RECEIVED.set(token);

        if (!INVENTORY_TOKEN.equals(token)) {
            respond(exchange, 401, "{}");
            return;
        }

        String rawQuery = exchange.getRequestURI().getRawQuery();
        String query = rawQuery == null ? ""
                : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
        INVENTORY_QUERIES.add(query);

        if (!"GET".equals(exchange.getRequestMethod())
                || !query.startsWith("sku_ids=")) {
            respond(exchange, 400, "{}");
            return;
        }

        List<String> stocks = new ArrayList<>();
        String[] ids = query.substring("sku_ids=".length()).split(",");

        for (String skuId : ids) {
            switch (skuId) {
                case "sku-001" -> stocks.add(STOCK_001);
                case "sku-002" -> stocks.add(STOCK_002);
                default -> {
                    respond(exchange, 400, "{}");
                    return;
                }
            }
        }

        respond(exchange, 200,
                "{\"data\":[" + String.join(",", stocks) + "]}");
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

    @AfterAll
    static void stopProviders() {
        if (productServer != null) productServer.stop(0);
        if (inventoryServer != null) inventoryServer.stop(0);
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
        PRODUCT_CALLS.set(0);
        INVENTORY_QUERIES.clear();
        INVENTORY_TOKEN_RECEIVED.set(null);
    }

    @Test
    void shouldCompleteAddViewUpdateCheckoutCleanupJourney() throws Exception {
        JsonNode first = data(addItem(BUYER, "sku-001", 2));
        String cartId = first.path("cart_id").asText();
        String itemId1 = first.path("item_id").asText();

        assertFalse(cartId.isBlank());
        assertFalse(itemId1.isBlank());
        assertEquals(2, first.path("quantity").asInt());
        assertEquals(498000L, first.path("line_total").asLong());

        JsonNode second = data(addItem(BUYER, "sku-002", 1));
        String itemId2 = second.path("item_id").asText();

        assertEquals(cartId, second.path("cart_id").asText());
        assertNotEquals(itemId1, itemId2);
        assertEquals(199000L, second.path("line_total").asLong());

        JsonNode initialCart = data(buyerCart(BUYER));
        assertEquals(cartId, initialCart.path("cart_id").asText());
        assertEquals(2, initialCart.path("item_count").asInt());

        JsonNode sku1 = findSku(initialCart.path("items"), "sku-001");
        JsonNode sku2 = findSku(initialCart.path("items"), "sku-002");

        assertEquals("AVAILABLE",
                sku1.path("availability_status").asText());
        assertEquals("AVAILABLE",
                sku2.path("availability_status").asText());
        assertEquals(2, sku1.path("quantity").asInt());
        assertEquals(1, sku2.path("quantity").asInt());

        JsonNode updated = data(mvc.perform(put(CART_PATH + "/items/" + itemId1)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":4}"))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals(itemId1, updated.path("item_id").asText());
        assertEquals(4, updated.path("quantity").asInt());
        assertEquals(996000L, updated.path("line_total").asLong());

        JsonNode updatedCart = data(buyerCart(BUYER));
        JsonNode updatedSku = findSku(updatedCart.path("items"), "sku-001");

        assertEquals(4, updatedSku.path("quantity").asInt());
        assertEquals(996000L, updatedSku.path("line_total").asLong());
        assertEquals("AVAILABLE",
                updatedSku.path("availability_status").asText());

        JsonNode checkoutView = data(internalCart(BUYER, itemId1));

        assertEquals(cartId, checkoutView.path("cart_id").asText());
        assertEquals(BUYER, checkoutView.path("buyer_user_id").asText());
        assertEquals(1, checkoutView.path("items").size());
        assertEquals(0, checkoutView.path("missing_item_ids").size());

        JsonNode checkoutItem = checkoutView.path("items").get(0);
        long revision = checkoutItem.path("item_revision").asLong();

        assertEquals(itemId1, checkoutItem.path("item_id").asText());
        assertEquals(4, checkoutItem.path("quantity").asInt());
        assertEquals(2L, revision);
        assertEquals(249000L,
                checkoutItem.path("unit_price_snapshot").asLong());

        JsonNode cleaned = cleanup(BUYER, cartId, itemId1, revision);

        assertEquals(cartId, cleaned.path("cart_id").asText());
        assertEquals(itemId1,
                cleaned.path("removed_item_ids").get(0).asText());
        assertEquals(0, cleaned.path("missing_item_ids").size());
        assertEquals(0, cleaned.path("skipped_modified_item_ids").size());
        assertEquals(1, cleaned.path("remaining_item_count").asInt());

        JsonNode finalCart = data(buyerCart(BUYER));

        assertEquals(cartId, finalCart.path("cart_id").asText());
        assertEquals(1, finalCart.path("item_count").asInt());
        assertEquals(itemId2,
                finalCart.path("items").get(0).path("item_id").asText());
        assertEquals("AVAILABLE",
                finalCart.path("items").get(0)
                        .path("availability_status").asText());

        Document persisted = mongoTemplate.getCollection("carts")
                .find(new Document("buyer_user_id", BUYER)).first();

        assertNotNull(persisted);
        assertEquals(cartId, persisted.getString("_id"));

        List<Document> items = persisted.getList("items", Document.class);
        assertEquals(1, items.size());
        assertEquals(itemId2, items.getFirst().getString("item_id"));
        assertEquals("sku-002", items.getFirst().getString("sku_id"));
        assertEquals(1, items.getFirst().getInteger("quantity"));
        assertEquals(1L, items.getFirst().getLong("item_revision"));
        assertEquals(199000L,
                items.getFirst().getLong("unit_price_snapshot"));

        assertTrue(PRODUCT_CALLS.get() >= 2);
        assertTrue(INVENTORY_QUERIES.contains(
                "sku_ids=sku-001,sku-002"));
        assertTrue(INVENTORY_QUERIES.contains("sku_ids=sku-002"));
        assertEquals(INVENTORY_TOKEN, INVENTORY_TOKEN_RECEIVED.get());
    }

    @Test
    void shouldKeepBuyerCartsIsolatedThroughoutCheckout() throws Exception {
        JsonNode first = data(addItem(BUYER, "sku-001", 1));
        JsonNode second = data(addItem(BUYER_2, "sku-002", 2));

        String cartId1 = first.path("cart_id").asText();
        String cartId2 = second.path("cart_id").asText();
        String itemId2 = second.path("item_id").asText();

        assertNotEquals(cartId1, cartId2);

        JsonNode buyer1 = data(buyerCart(BUYER));
        JsonNode buyer2 = data(buyerCart(BUYER_2));

        assertEquals(1, buyer1.path("item_count").asInt());
        assertEquals(1, buyer2.path("item_count").asInt());
        assertEquals("sku-001",
                buyer1.path("items").get(0).path("sku_id").asText());
        assertEquals("sku-002",
                buyer2.path("items").get(0).path("sku_id").asText());

        JsonNode checkoutView = data(internalCart(BUYER_2, itemId2));
        long revision = checkoutView.path("items").get(0)
                .path("item_revision").asLong();

        JsonNode cleaned = cleanup(BUYER_2, cartId2, itemId2, revision);
        assertEquals(0, cleaned.path("remaining_item_count").asInt());

        JsonNode afterBuyer1 = data(buyerCart(BUYER));
        JsonNode afterBuyer2 = data(buyerCart(BUYER_2));

        assertEquals(cartId1, afterBuyer1.path("cart_id").asText());
        assertEquals(1, afterBuyer1.path("item_count").asInt());
        assertEquals(0, afterBuyer2.path("item_count").asInt());

        assertEquals(2L, mongoTemplate.getCollection("carts")
                .countDocuments());
    }

    private MvcResult addItem(String buyer, String skuId, int quantity)
            throws Exception {
        String body = """
                {
                  "product_id": "product-001",
                  "sku_id": "%s",
                  "quantity": %d
                }
                """.formatted(skuId, quantity);

        return mvc.perform(post(CART_PATH + "/items")
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult buyerCart(String buyer) throws Exception {
        return mvc.perform(get(CART_PATH)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", buyer))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult internalCart(String buyer, String itemId)
            throws Exception {
        return mvc.perform(get(INTERNAL_PATH + "/" + buyer)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("item_ids", itemId))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JsonNode cleanup(String buyer, String cartId,
                             String itemId, long revision) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "cart_id", cartId,
                "checkout_group_id", "group-e2e-001",
                "item_ids", List.of(itemId),
                "expected_item_revisions", Map.of(itemId, revision)
        ));

        MvcResult result = mvc.perform(
                        post(INTERNAL_PATH + "/" + buyer + "/checked-out")
                                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return data(result);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString())
                .path("data");
    }

    private JsonNode findSku(JsonNode items, String skuId) {
        for (JsonNode item : items) {
            if (skuId.equals(item.path("sku_id").asText())) return item;
        }
        throw new AssertionError("SKU not found: " + skuId);
    }
}

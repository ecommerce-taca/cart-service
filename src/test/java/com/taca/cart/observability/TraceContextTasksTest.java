package com.taca.cart.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taca.cart.application.ProductHydrationResult;
import com.taca.cart.config.InventoryProperties;
import com.taca.cart.config.ProductProperties;
import com.taca.cart.integrations.HttpInventoryAvailabilityClient;
import com.taca.cart.integrations.HttpProductHydrationClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OutboundHttpTracingTest {
    private static final String TRACE_ID =
            "11223344556677889900aabbccddeeff";
    private static final String PARENT_SPAN_ID =
            "1234567890abcdef";

    private static final String PRODUCT_JSON = """
            {
              "data": {
                "product_id": "product-001",
                "status": "ACTIVE",
                "title": "Cotton jacket",
                "shop": {"shop_id": "shop-001"},
                "skus": [{
                  "sku_id": "sku-001",
                  "status": "ACTIVE",
                  "attributes": {"color": "black"},
                  "price": {"sale_price": 249000, "currency": "VND"}
                }]
              }
            }
            """;

    private static final String INVENTORY_JSON = """
            {
              "data": [{
                "sku_id": "sku-001",
                "available_qty": 7,
                "status": "ACTIVE",
                "as_of": "2026-11-08T00:00:00Z"
              }]
            }
            """;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private String baseUrl;
    private SdkTracerProvider tracerProvider;
    private OutboundHttpTracing tracing;

    private final AtomicReference<String> productTraceparent =
            new AtomicReference<>();
    private final AtomicReference<String> inventoryTraceparent =
            new AtomicReference<>();
    private final AtomicReference<String> productTracestate =
            new AtomicReference<>();
    private final AtomicReference<String> inventoryTracestate =
            new AtomicReference<>();
    private final AtomicReference<String> productRequestId =
            new AtomicReference<>();
    private final AtomicReference<String> inventoryRequestId =
            new AtomicReference<>();
    private final AtomicReference<String> inventoryToken =
            new AtomicReference<>();

    private volatile int productStatus;
    private volatile int inventoryStatus;

    @BeforeEach
    void setUp() throws Exception {
        productStatus = 200;
        inventoryStatus = 200;

        tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(
                        W3CTraceContextPropagator.getInstance()))
                .build();

        tracing = new OutboundHttpTracing(telemetry);

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);

        server.createContext("/api/v1/products/", exchange -> {
            productTraceparent.set(
                    exchange.getRequestHeaders().getFirst("traceparent"));
            productTracestate.set(
                    exchange.getRequestHeaders().getFirst("tracestate"));
            productRequestId.set(
                    exchange.getRequestHeaders().getFirst("X-Request-ID"));

            respond(exchange, productStatus, PRODUCT_JSON);
        });

        server.createContext("/internal/inventory/availability", exchange -> {
            inventoryTraceparent.set(
                    exchange.getRequestHeaders().getFirst("traceparent"));
            inventoryTracestate.set(
                    exchange.getRequestHeaders().getFirst("tracestate"));
            inventoryRequestId.set(
                    exchange.getRequestHeaders().getFirst("X-Request-ID"));
            inventoryToken.set(
                    exchange.getRequestHeaders()
                            .getFirst("X-Internal-Service-Token"));

            respond(exchange, inventoryStatus, INVENTORY_JSON);
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
        if (tracerProvider != null) tracerProvider.shutdown();
    }

    @Test
    void shouldPropagateW3cContextThroughProductAndInventory()
            throws Exception {
        SpanContext parent = remoteParent();

        try (Scope ignored = Context.current()
                .with(Span.wrap(parent)).makeCurrent()) {
            MDC.put("request_id", "req-cart-001");

            assertEquals(ProductHydrationResult.State.FOUND,
                    product().fetchProduct("product-001").state());

            assertEquals(7L,
                    inventory().fetchAvailability(Set.of("sku-001"))
                            .get("sku-001").availableQty());
        }

        assertChildTraceparent(productTraceparent.get());
        assertChildTraceparent(inventoryTraceparent.get());

        assertNotEquals(productTraceparent.get(),
                inventoryTraceparent.get());

        assertEquals("acme=v1", productTracestate.get());
        assertEquals("acme=v1", inventoryTracestate.get());

        assertEquals("req-cart-001", productRequestId.get());
        assertEquals("req-cart-001", inventoryRequestId.get());
        assertEquals("internal-test-token", inventoryToken.get());
    }

    @Test
    void shouldPropagateTraceAcrossVirtualThreadTasks()
            throws Exception {
        ExecutorService workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory());

        try {
            SpanContext parent = remoteParent();

            try (Scope ignored = Context.current()
                    .with(Span.wrap(parent)).makeCurrent()) {
                MDC.put("request_id", "req-virtual-001");

                Future<ProductHydrationResult> productFuture = workers.submit(
                        TraceContextTasks.wrap(() ->
                                product().fetchProduct("product-001")));

                Future<Map<String, com.taca.cart.application.InventoryStock>>
                        inventoryFuture = workers.submit(
                        TraceContextTasks.wrap(() ->
                                inventory().fetchAvailability(
                                        Set.of("sku-001"))));

                assertEquals(ProductHydrationResult.State.FOUND,
                        productFuture.get().state());
                assertEquals(7L,
                        inventoryFuture.get().get("sku-001").availableQty());
            }
        } finally {
            workers.shutdownNow();
        }

        assertChildTraceparent(productTraceparent.get());
        assertChildTraceparent(inventoryTraceparent.get());
        assertEquals("req-virtual-001", productRequestId.get());
        assertEquals("req-virtual-001", inventoryRequestId.get());
    }

    @Test
    void shouldKeepExistingFallbackBehaviorForHttpErrors()
            throws Exception {
        productStatus = 503;
        inventoryStatus = 503;

        try (Scope ignored = Context.current()
                .with(Span.wrap(remoteParent())).makeCurrent()) {

            assertEquals(ProductHydrationResult.State.STALE,
                    product().fetchProduct("product-001").state());

            assertTrue(inventory()
                    .fetchAvailability(Set.of("sku-001")).isEmpty());
        }

        assertChildTraceparent(productTraceparent.get());
        assertChildTraceparent(inventoryTraceparent.get());
    }

    private SpanContext remoteParent() {
        TraceState traceState = TraceState.builder()
                .put("acme", "v1")
                .build();

        return SpanContext.createFromRemoteParent(
                TRACE_ID, PARENT_SPAN_ID,
                TraceFlags.getSampled(), traceState);
    }

    private void assertChildTraceparent(String header) {
        assertNotNull(header);
        assertTrue(header.startsWith("00-" + TRACE_ID + "-"),
                "Trace ID was not preserved: " + header);

        String[] parts = header.split("-");
        assertEquals(4, parts.length);
        assertEquals(16, parts[2].length());
        assertNotEquals(PARENT_SPAN_ID, parts[2]);
        assertEquals("01", parts[3]);
    }

    private HttpProductHydrationClient product() {
        ProductProperties properties = new ProductProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(30), 10);

        return new HttpProductHydrationClient(
                properties, baseUrl, null, tracing);
    }

    private HttpInventoryAvailabilityClient inventory() {
        InventoryProperties properties = new InventoryProperties(
                Duration.ofSeconds(2), 100, 10);

        return new HttpInventoryAvailabilityClient(
                properties, baseUrl, "internal-test-token",
                null, tracing);
    }

    private void respond(
            HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);

            exchange.getResponseHeaders()
                    .set("Content-Type", "application/json");

            exchange.sendResponseHeaders(status, bytes.length);

            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // HTTP client may disconnect.
        } finally {
            exchange.close();
        }
    }
}

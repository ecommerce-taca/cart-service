package com.taca.cart.observability;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartItemResponse;
import com.taca.cart.api.dto.CartResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.taca.cart.observability.CartDependencyMetrics.*;
import static org.junit.jupiter.api.Assertions.*;

class CartDependencyMetricsTest {
    private SimpleMeterRegistry registry;
    private CartDependencyMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CartDependencyMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void shouldRegisterAllBoundedOutcomes() {
        for (ProductHttpOutcome outcome : ProductHttpOutcome.values()) {
            String label = outcome.name().toLowerCase(java.util.Locale.ROOT);
            assertNotNull(registry.get("cart.product.http.requests")
                    .tag("outcome", label).counter());
            assertNotNull(registry.get("cart.product.http.duration")
                    .tag("outcome", label).timer());
        }

        for (InventoryHttpOutcome outcome : InventoryHttpOutcome.values()) {
            String label = outcome.name().toLowerCase(java.util.Locale.ROOT);
            assertNotNull(registry.get("cart.inventory.http.requests")
                    .tag("outcome", label).counter());
            assertNotNull(registry.get("cart.inventory.http.duration")
                    .tag("outcome", label).timer());
        }
    }

    @Test
    void shouldRecordProductHttpCountAndDuration() {
        metrics.recordProductHttp(ProductHttpOutcome.FOUND,
                TimeUnit.MILLISECONDS.toNanos(12));
        metrics.recordProductHttp(ProductHttpOutcome.FOUND,
                TimeUnit.MILLISECONDS.toNanos(8));
        metrics.recordProductHttp(ProductHttpOutcome.TIMEOUT,
                TimeUnit.MILLISECONDS.toNanos(50));

        assertEquals(2.0, productCount("found"));
        assertEquals(1.0, productCount("timeout"));

        var timer = registry.get("cart.product.http.duration")
                .tag("outcome", "found").timer();

        assertEquals(2L, timer.count());
        assertEquals(20.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void shouldRecordInventoryHttpCountAndDuration() {
        metrics.recordInventoryHttp(InventoryHttpOutcome.COMPLETE,
                TimeUnit.MILLISECONDS.toNanos(10));
        metrics.recordInventoryHttp(InventoryHttpOutcome.PARTIAL,
                TimeUnit.MILLISECONDS.toNanos(20));

        assertEquals(1.0, inventoryCount("complete"));
        assertEquals(1.0, inventoryCount("partial"));

        var timer = registry.get("cart.inventory.http.duration")
                .tag("outcome", "partial").timer();

        assertEquals(1L, timer.count());
        assertEquals(20.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void shouldRecordCacheAndFallbackSeparately() {
        metrics.recordCacheLookup(CacheLookup.MISS);
        metrics.recordCacheLookup(CacheLookup.HIT);
        metrics.recordCacheLookup(CacheLookup.HIT);
        metrics.recordCacheLookup(CacheLookup.SHARED);
        metrics.recordStaleFallback();

        assertEquals(1.0, cacheCount("miss"));
        assertEquals(2.0, cacheCount("hit"));
        assertEquals(1.0, cacheCount("shared"));
        assertEquals(1.0, registry.get("cart.product.stale.fallbacks")
                .counter().count());
    }

    @Test
    void shouldRecordInventorySkipsWithoutHttpCalls() {
        metrics.recordInventorySkipped(InventorySkipReason.INVALID);
        metrics.recordInventorySkipped(InventorySkipReason.NOT_CONFIGURED);
        metrics.recordInventorySkipped(InventorySkipReason.BUSY);

        assertEquals(1.0, skipCount("invalid"));
        assertEquals(1.0, skipCount("not_configured"));
        assertEquals(1.0, skipCount("busy"));

        for (InventoryHttpOutcome outcome : InventoryHttpOutcome.values()) {
            String label = outcome.name().toLowerCase(java.util.Locale.ROOT);
            assertEquals(0.0, inventoryCount(label));
        }
    }

    @Test
    void shouldCountFinalAvailabilityPerItem() {
        CartResponse response = new CartResponse(
                "cart-001",
                List.of(
                        item("001", AvailabilityStatus.AVAILABLE),
                        item("002", AvailabilityStatus.UNKNOWN),
                        item("003", AvailabilityStatus.UNKNOWN),
                        item("004", AvailabilityStatus.STALE)
                ),
                4, java.time.Instant.parse("2026-11-08T00:00:00Z"));

        metrics.recordHydrationItems(response);

        assertEquals(1.0, hydrationCount("available"));
        assertEquals(2.0, hydrationCount("unknown"));
        assertEquals(1.0, hydrationCount("stale"));
        assertEquals(0.0, hydrationCount("out_of_stock"));
        assertEquals(0.0, hydrationCount("product_unavailable"));
    }

    private CartItemResponse item(String id, AvailabilityStatus status) {
        return new CartItemResponse(
                "item-" + id, "product-" + id, "sku-" + id,
                "shop-001", "Product", "Default",
                1000L, 1, 1000L, true, status);
    }

    private double productCount(String outcome) {
        return registry.get("cart.product.http.requests")
                .tag("outcome", outcome).counter().count();
    }

    private double inventoryCount(String outcome) {
        return registry.get("cart.inventory.http.requests")
                .tag("outcome", outcome).counter().count();
    }

    private double cacheCount(String result) {
        return registry.get("cart.product.cache.lookups")
                .tag("result", result).counter().count();
    }

    private double skipCount(String reason) {
        return registry.get("cart.inventory.skipped")
                .tag("reason", reason).counter().count();
    }

    private double hydrationCount(String status) {
        return registry.get("cart.hydration.items")
                .tag("availability_status", status).counter().count();
    }
}

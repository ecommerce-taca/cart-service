package com.taca.cart.observability;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class CartDependencyMetrics {
    public enum ProductHttpOutcome {
        FOUND, UNAVAILABLE, NOT_FOUND, INVALID_RESPONSE,
        HTTP_ERROR, TIMEOUT, IO_ERROR, INTERRUPTED
    }

    public enum InventoryHttpOutcome {
        COMPLETE, PARTIAL, EMPTY, HTTP_ERROR,
        TIMEOUT, IO_ERROR, INTERRUPTED, CLIENT_ERROR
    }

    public enum CacheLookup {
        HIT, MISS, SHARED
    }

    public enum InventorySkipReason {
        EMPTY, INVALID, NOT_CONFIGURED, BUSY
    }

    private final EnumMap<ProductHttpOutcome, Counter> productHttpCounters =
            new EnumMap<>(ProductHttpOutcome.class);
    private final EnumMap<ProductHttpOutcome, Timer> productHttpTimers =
            new EnumMap<>(ProductHttpOutcome.class);
    private final EnumMap<InventoryHttpOutcome, Counter> inventoryHttpCounters =
            new EnumMap<>(InventoryHttpOutcome.class);
    private final EnumMap<InventoryHttpOutcome, Timer> inventoryHttpTimers =
            new EnumMap<>(InventoryHttpOutcome.class);
    private final EnumMap<CacheLookup, Counter> cacheCounters =
            new EnumMap<>(CacheLookup.class);
    private final EnumMap<InventorySkipReason, Counter> inventorySkipCounters =
            new EnumMap<>(InventorySkipReason.class);
    private final EnumMap<AvailabilityStatus, Counter> hydrationCounters =
            new EnumMap<>(AvailabilityStatus.class);

    private final Counter staleFallbacks;

    public CartDependencyMetrics(MeterRegistry registry) {
        for (ProductHttpOutcome outcome : ProductHttpOutcome.values()) {
            String label = label(outcome);
            productHttpCounters.put(outcome, Counter.builder("cart.product.http.requests")
                    .description("Outbound Product Catalog HTTP attempts")
                    .tag("outcome", label).register(registry));
            productHttpTimers.put(outcome, Timer.builder("cart.product.http.duration")
                    .description("Outbound Product Catalog HTTP duration")
                    .tag("outcome", label).register(registry));
        }

        for (InventoryHttpOutcome outcome : InventoryHttpOutcome.values()) {
            String label = label(outcome);
            inventoryHttpCounters.put(outcome, Counter.builder("cart.inventory.http.requests")
                    .description("Outbound Inventory HTTP attempts")
                    .tag("outcome", label).register(registry));
            inventoryHttpTimers.put(outcome, Timer.builder("cart.inventory.http.duration")
                    .description("Outbound Inventory HTTP duration")
                    .tag("outcome", label).register(registry));
        }

        for (CacheLookup result : CacheLookup.values()) {
            cacheCounters.put(result, Counter.builder("cart.product.cache.lookups")
                    .description("Product hydration cache lookup decisions")
                    .tag("result", label(result)).register(registry));
        }

        for (InventorySkipReason reason : InventorySkipReason.values()) {
            inventorySkipCounters.put(reason, Counter.builder("cart.inventory.skipped")
                    .description("Inventory client calls without an outbound HTTP request")
                    .tag("reason", label(reason)).register(registry));
        }

        for (AvailabilityStatus status : AvailabilityStatus.values()) {
            hydrationCounters.put(status, Counter.builder("cart.hydration.items")
                    .description("Final buyer cart item availability results")
                    .tag("availability_status", label(status))
                    .register(registry));
        }

        staleFallbacks = Counter.builder("cart.product.stale.fallbacks")
                .description("Product hydration lookups returning STALE")
                .register(registry);
    }

    public void recordProductHttp(ProductHttpOutcome outcome, long elapsedNanos) {
        productHttpCounters.get(outcome).increment();
        productHttpTimers.get(outcome).record(
                Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordInventoryHttp(InventoryHttpOutcome outcome, long elapsedNanos) {
        inventoryHttpCounters.get(outcome).increment();
        inventoryHttpTimers.get(outcome).record(
                Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordCacheLookup(CacheLookup result) {
        cacheCounters.get(result).increment();
    }

    public void recordStaleFallback() {
        staleFallbacks.increment();
    }

    public void recordInventorySkipped(InventorySkipReason reason) {
        inventorySkipCounters.get(reason).increment();
    }

    public void recordHydrationItems(CartResponse response) {
        response.items().forEach(item ->
                hydrationCounters.get(item.availabilityStatus()).increment());
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}

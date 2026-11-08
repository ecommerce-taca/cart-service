
package com.taca.cart.application;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.config.CartProperties;
import com.taca.cart.config.InventoryProperties;
import com.taca.cart.config.ProductProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CartHydrationOrchestratorTest {
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    private final CachedProductHydrationService products =
            mock(CachedProductHydrationService.class);
    private final InventoryAvailabilityClient inventory =
            mock(InventoryAvailabilityClient.class);

    @Test
    void shouldReturnEmptyCartWithoutCallingDependencies() {
        CartResponse result = orchestrator().hydrate(Optional.empty());

        assertNull(result.cartId());
        assertNull(result.updatedAt());
        assertEquals(0, result.itemCount());
        assertTrue(result.items().isEmpty());
        verifyNoInteractions(products, inventory);
    }

    @Test
    void shouldSkipDependenciesForExistingEmptyCart() {
        Cart cart = cart(List.of());

        CartResponse result = orchestrator().hydrate(Optional.of(cart));

        assertEquals("cart-001", result.cartId());
        assertEquals(0, result.itemCount());
        assertEquals(NOW, result.updatedAt());
        verifyNoInteractions(products, inventory);
    }

    @Test
    void shouldHydrateProductAndInventoryWithoutChangingCart() {
        Cart cart = cart(List.of(item("product-001", "sku-001")));
        when(products.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));
        when(inventory.fetchAvailability(Set.of("sku-001")))
                .thenReturn(Map.of("sku-001", stock("sku-001", 7)));

        CartResponse result = orchestrator().hydrate(Optional.of(cart));
        var responseItem = result.items().getFirst();

        assertEquals(AvailabilityStatus.AVAILABLE, responseItem.availabilityStatus());
        assertTrue(responseItem.available());
        assertEquals("Current title", responseItem.titleSnapshot());
        assertEquals(229000L, responseItem.unitPriceSnapshot());
        assertEquals(458000L, responseItem.lineTotal());
        assertEquals(2, responseItem.quantity());

        assertEquals(249000L, cart.items().getFirst().unitPriceSnapshot());
        assertEquals(3L, cart.version());
        assertEquals(2L, cart.items().getFirst().itemRevision());
        assertEquals(NOW, cart.updatedAt());

        verify(products).getProduct("product-001");
        verify(inventory).fetchAvailability(Set.of("sku-001"));
    }

    @Test
    void shouldFetchSharedProductOnceAndBatchItsSkus() {
        Cart cart = cart(List.of(
                item("product-001", "sku-001"),
                item("product-001", "sku-002")));

        when(products.getProduct("product-001")).thenReturn(
                ProductHydrationResult.found(Map.of(
                        "sku-001", sku("product-001", "sku-001", 229000L),
                        "sku-002", sku("product-001", "sku-002", 199000L))));

        when(inventory.fetchAvailability(Set.of("sku-001", "sku-002")))
                .thenReturn(Map.of(
                        "sku-001", stock("sku-001", 5),
                        "sku-002", stock("sku-002", 1)));

        CartResponse result = orchestrator().hydrate(Optional.of(cart));

        assertEquals(2, result.itemCount());
        assertEquals(AvailabilityStatus.AVAILABLE,
                result.items().get(0).availabilityStatus());
        assertEquals(AvailabilityStatus.OUT_OF_STOCK,
                result.items().get(1).availabilityStatus());

        verify(products, times(1)).getProduct("product-001");
        verify(inventory, times(1))
                .fetchAvailability(Set.of("sku-001", "sku-002"));
    }

    @Test
    void shouldSkipInventoryWhenProductIsUnavailable() {
        when(products.getProduct("product-001"))
                .thenReturn(ProductHydrationResult.unavailable());

        CartResponse result = orchestrator().hydrate(
                Optional.of(cart(List.of(item("product-001", "sku-001")))));

        assertEquals(AvailabilityStatus.PRODUCT_UNAVAILABLE,
                result.items().getFirst().availabilityStatus());
        assertFalse(result.items().getFirst().available());
        verifyNoInteractions(inventory);
    }

    @Test
    void shouldSkipInventoryWhenProductIsStale() {
        when(products.getProduct("product-001"))
                .thenReturn(ProductHydrationResult.stale());

        CartResponse result = orchestrator().hydrate(
                Optional.of(cart(List.of(item("product-001", "sku-001")))));

        assertEquals(AvailabilityStatus.STALE,
                result.items().getFirst().availabilityStatus());
        verifyNoInteractions(inventory);
    }

    @Test
    void shouldSkipInventoryWhenSkuIsNotInProduct() {
        when(products.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-other", 229000L));

        CartResponse result = orchestrator().hydrate(
                Optional.of(cart(List.of(item("product-001", "sku-001")))));

        assertEquals(AvailabilityStatus.PRODUCT_UNAVAILABLE,
                result.items().getFirst().availabilityStatus());
        verifyNoInteractions(inventory);
    }

    @Test
    void shouldKeepUnknownWhenInventoryFails() {
        when(products.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));
        when(inventory.fetchAvailability(anySet()))
                .thenThrow(new IllegalStateException("Inventory unavailable"));

        CartResponse result = orchestrator().hydrate(
                Optional.of(cart(List.of(item("product-001", "sku-001")))));

        assertEquals(AvailabilityStatus.UNKNOWN,
                result.items().getFirst().availabilityStatus());
        assertTrue(result.items().getFirst().available());
        assertEquals(229000L, result.items().getFirst().unitPriceSnapshot());
    }

    @Test
    void shouldKeepUnknownWhenInventoryReturnsNoData() {
        when(products.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));
        when(inventory.fetchAvailability(anySet())).thenReturn(Map.of());

        CartResponse result = orchestrator().hydrate(
                Optional.of(cart(List.of(item("product-001", "sku-001")))));

        assertEquals(AvailabilityStatus.UNKNOWN,
                result.items().getFirst().availabilityStatus());
        assertTrue(result.items().getFirst().available());
    }

    @Test
    void shouldRespectTotalBudgetWhenInventoryBlocks() {
        when(products.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));

        when(inventory.fetchAvailability(anySet())).thenAnswer(invocation -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        });

        var service = orchestrator(
                Duration.ofMillis(450),
                Duration.ofMillis(250),
                Duration.ofMillis(300), 10, 100);

        long started = System.nanoTime();
        CartResponse result = service.hydrate(
                Optional.of(cart(List.of(item("product-001", "sku-001")))));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertEquals(AvailabilityStatus.UNKNOWN,
                result.items().getFirst().availabilityStatus());
        assertTrue(elapsedMillis < 2000,
                "Hydration exceeded reasonable deadline overhead: " + elapsedMillis);
    }

    @Test
    void shouldPreserveFastProductWhenAnotherProductTimesOut() {
        when(products.getProduct("product-001"))
                .thenReturn(found("product-001", "sku-001", 229000L));

        when(products.getProduct("product-002")).thenAnswer(invocation -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ProductHydrationResult.stale();
        });

        when(inventory.fetchAvailability(Set.of("sku-001")))
                .thenReturn(Map.of("sku-001", stock("sku-001", 7)));

        var service = orchestrator(
                Duration.ofMillis(700),
                Duration.ofMillis(350),
                Duration.ofMillis(250), 10, 100);

        CartResponse result = service.hydrate(Optional.of(cart(List.of(
                item("product-001", "sku-001"),
                item("product-002", "sku-002")))));

        assertEquals(AvailabilityStatus.AVAILABLE,
                result.items().get(0).availabilityStatus());
        assertEquals(AvailabilityStatus.STALE,
                result.items().get(1).availabilityStatus());

        verify(inventory).fetchAvailability(Set.of("sku-001"));
    }

    @Test
    void shouldSplitInventoryIntoConfiguredBatches() {
        when(products.getProduct("product-001")).thenReturn(
                ProductHydrationResult.found(Map.of(
                        "sku-001", sku("product-001", "sku-001", 229000L),
                        "sku-002", sku("product-001", "sku-002", 199000L))));

        when(inventory.fetchAvailability(Set.of("sku-001")))
                .thenReturn(Map.of("sku-001", stock("sku-001", 7)));
        when(inventory.fetchAvailability(Set.of("sku-002")))
                .thenReturn(Map.of("sku-002", stock("sku-002", 0)));

        var service = orchestrator(
                Duration.ofSeconds(3), Duration.ofSeconds(2),
                Duration.ofSeconds(1), 10, 1);

        CartResponse result = service.hydrate(Optional.of(cart(List.of(
                item("product-001", "sku-001"),
                item("product-001", "sku-002")))));

        assertEquals(AvailabilityStatus.AVAILABLE,
                result.items().get(0).availabilityStatus());
        assertEquals(AvailabilityStatus.OUT_OF_STOCK,
                result.items().get(1).availabilityStatus());

        verify(inventory).fetchAvailability(Set.of("sku-001"));
        verify(inventory).fetchAvailability(Set.of("sku-002"));
        verifyNoMoreInteractions(inventory);
    }

    @Test
    void shouldBoundParallelProductTasks() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        when(products.getProduct(anyString())).thenAnswer(invocation -> {
            String productId = invocation.getArgument(0);
            String skuId = productId.replace("product-", "sku-");
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);

            try {
                Thread.sleep(80);
                return found(productId, skuId, 229000L);
            } finally {
                active.decrementAndGet();
            }
        });

        when(inventory.fetchAvailability(anySet())).thenReturn(Map.of());

        List<CartItem> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(item("product-" + i, "sku-" + i));
        }

        var service = orchestrator(
                Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofMillis(300), 2, 100);

        CartResponse result = service.hydrate(Optional.of(cart(items)));

        assertEquals(8, result.itemCount());
        assertTrue(maximum.get() <= 2);
        verify(products, times(8)).getProduct(anyString());
    }

    @Test
    void shouldRejectNonPositiveHydrationBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator(Duration.ZERO, Duration.ofSeconds(2),
                        Duration.ofSeconds(1), 10, 100));
    }

    private CartHydrationOrchestrator orchestrator() {
        return orchestrator(Duration.ofSeconds(3), Duration.ofSeconds(2),
                Duration.ofSeconds(1), 10, 100);
    }

    private CartHydrationOrchestrator orchestrator(
            Duration total, Duration productTimeout,
            Duration inventoryTimeout, int concurrency, int batchSize) {
        CartProperties cartProperties = new CartProperties(
                100, 1, 999, total, Duration.ofHours(24), 3);
        ProductProperties productProperties = new ProductProperties(
                productTimeout, Duration.ofSeconds(30), concurrency);
        InventoryProperties inventoryProperties = new InventoryProperties(
                inventoryTimeout, batchSize, 10);

        return new CartHydrationOrchestrator(
                products, new CartProductResponseHydrator(),
                inventory, new CartInventoryResponseHydrator(),
                cartProperties, productProperties, inventoryProperties);
    }

    private Cart cart(List<CartItem> items) {
        return new Cart("cart-001", "buyer-001", items, 3L, NOW, NOW);
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
                skuId, sku(productId, skuId, price)));
    }

    private ValidatedSkuSnapshot sku(
            String productId, String skuId, long price) {
        return new ValidatedSkuSnapshot(
                productId, skuId, "shop-001",
                "Current title", "Black / L", price);
    }

    private InventoryStock stock(String skuId, long quantity) {
        return new InventoryStock(skuId, quantity, "ACTIVE", NOW);
    }
}

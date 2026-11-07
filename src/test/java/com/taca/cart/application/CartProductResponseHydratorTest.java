
package com.taca.cart.application;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartItemResponse;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CartProductResponseHydratorTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private final CartProductResponseHydrator hydrator = new CartProductResponseHydrator();

    @Test
    void shouldReturnEmptyCartWithoutProductData() {
        var response = hydrator.hydrate(Optional.empty(), Map.of());

        assertNull(response.cartId());
        assertNull(response.updatedAt());
        assertEquals(0, response.itemCount());
        assertTrue(response.items().isEmpty());
    }

    @Test
    void shouldHydrateCurrentTitleLabelAndPrice() {
        Cart cart = cart(List.of(item("item-001", "sku-001", 249000L, true)));
        var result = ProductHydrationResult.found(Map.of(
                "sku-001", sku("sku-001", 229000L)));

        var response = hydrator.hydrate(Optional.of(cart),
                Map.of("product-001", result));

        CartItemResponse updated = response.items().getFirst();

        assertEquals("New title", updated.titleSnapshot());
        assertEquals("Black / L", updated.skuLabelSnapshot());
        assertEquals(229000L, updated.unitPriceSnapshot());
        assertEquals(458000L, updated.lineTotal());
        assertEquals(2, updated.quantity());
        assertEquals(AvailabilityStatus.UNKNOWN, updated.availabilityStatus());
        assertTrue(updated.available());
    }

    @Test
    void shouldUseSameProductResultForDifferentSkus() {
        Cart cart = cart(List.of(
                item("item-001", "sku-001", 249000L, true),
                item("item-002", "sku-002", 199000L, true)));

        var result = ProductHydrationResult.found(Map.of(
                "sku-001", sku("sku-001", 229000L),
                "sku-002", sku("sku-002", 179000L)));

        var response = hydrator.hydrate(Optional.of(cart),
                Map.of("product-001", result));

        assertEquals(2, response.itemCount());
        assertEquals(229000L, response.items().get(0).unitPriceSnapshot());
        assertEquals(179000L, response.items().get(1).unitPriceSnapshot());
        assertEquals(458000L, response.items().get(0).lineTotal());
        assertEquals(358000L, response.items().get(1).lineTotal());
    }

    @Test
    void shouldMarkUnavailableProductWithoutChangingSnapshot() {
        Cart cart = cart(List.of(item("item-001", "sku-001", 249000L, true)));

        var response = hydrator.hydrate(Optional.of(cart),
                Map.of("product-001", ProductHydrationResult.unavailable()));

        CartItemResponse item = response.items().getFirst();
        assertEquals(AvailabilityStatus.PRODUCT_UNAVAILABLE, item.availabilityStatus());
        assertFalse(item.available());
        assertEquals("Old title", item.titleSnapshot());
        assertEquals(249000L, item.unitPriceSnapshot());
        assertEquals(498000L, item.lineTotal());
    }

    @Test
    void shouldMarkMissingSkuAsProductUnavailable() {
        Cart cart = cart(List.of(item("item-001", "sku-001", 249000L, true)));
        var result = ProductHydrationResult.found(Map.of(
                "sku-002", sku("sku-002", 229000L)));

        var response = hydrator.hydrate(Optional.of(cart),
                Map.of("product-001", result));

        assertEquals(AvailabilityStatus.PRODUCT_UNAVAILABLE,
                response.items().getFirst().availabilityStatus());
        assertFalse(response.items().getFirst().available());
    }

    @Test
    void shouldPreserveSnapshotWhenProductIsStale() {
        Cart cart = cart(List.of(item("item-001", "sku-001", 249000L, true)));

        var response = hydrator.hydrate(Optional.of(cart),
                Map.of("product-001", ProductHydrationResult.stale()));

        CartItemResponse item = response.items().getFirst();
        assertEquals(AvailabilityStatus.STALE, item.availabilityStatus());
        assertTrue(item.available());
        assertEquals("Old title", item.titleSnapshot());
        assertEquals(249000L, item.unitPriceSnapshot());
        assertEquals(498000L, item.lineTotal());
    }

    @Test
    void shouldUseStaleFallbackWhenProductResultIsMissing() {
        Cart cart = cart(List.of(item("item-001", "sku-001", 249000L, false)));

        var response = hydrator.hydrate(Optional.of(cart), Map.of());

        assertEquals(AvailabilityStatus.STALE,
                response.items().getFirst().availabilityStatus());
        assertFalse(response.items().getFirst().available());
        assertEquals(249000L, response.items().getFirst().unitPriceSnapshot());
    }

    @Test
    void shouldNotTrustMismatchedProductSnapshot() {
        Cart cart = cart(List.of(item("item-001", "sku-001", 249000L, true)));
        var mismatched = new ValidatedSkuSnapshot(
                "product-other", "sku-001", "shop-001",
                "Unexpected title", "Unexpected label", 1000L);

        var response = hydrator.hydrate(Optional.of(cart),
                Map.of("product-001",
                        ProductHydrationResult.found(Map.of("sku-001", mismatched))));

        CartItemResponse item = response.items().getFirst();
        assertEquals(AvailabilityStatus.STALE, item.availabilityStatus());
        assertEquals("Old title", item.titleSnapshot());
        assertEquals(249000L, item.unitPriceSnapshot());
    }

    @Test
    void shouldNotMutateOriginalCart() {
        Cart cart = cart(List.of(item("item-001", "sku-001", 249000L, true)));
        CartItem beforeItem = cart.items().getFirst();

        var response = hydrator.hydrate(Optional.of(cart),
                Map.of("product-001",
                        ProductHydrationResult.found(Map.of(
                                "sku-001", sku("sku-001", 229000L)))));

        assertEquals(7L, cart.version());
        assertEquals(3L, cart.items().getFirst().itemRevision());
        assertEquals(NOW, cart.updatedAt());
        assertEquals(beforeItem, cart.items().getFirst());
        assertEquals(249000L, cart.items().getFirst().unitPriceSnapshot());

        assertEquals(cart.id(), response.cartId());
        assertEquals(NOW, response.updatedAt());
        assertEquals(229000L, response.items().getFirst().unitPriceSnapshot());
    }

    @Test
    void shouldRejectMismatchedSkuMapKey() {
        assertThrows(IllegalArgumentException.class,
                () -> ProductHydrationResult.found(Map.of(
                        "sku-wrong", sku("sku-001", 229000L))));
    }

    @Test
    void shouldKeepProductResultImmutable() {
        Map<String, ValidatedSkuSnapshot> input = new HashMap<>();
        input.put("sku-001", sku("sku-001", 229000L));

        ProductHydrationResult result = ProductHydrationResult.found(input);
        input.clear();

        assertEquals(1, result.skus().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.skus().clear());
    }

    private Cart cart(List<CartItem> items) {
        return new Cart("cart-001", "buyer-001", items, 7L, NOW, NOW);
    }

    private CartItem item(String itemId, String skuId,
                          long price, boolean available) {
        return new CartItem(
                itemId, "product-001", skuId, "shop-001",
                2, 3L, "Old title", "Old label",
                price, available, NOW, NOW);
    }

    private ValidatedSkuSnapshot sku(String skuId, long price) {
        return new ValidatedSkuSnapshot(
                "product-001", skuId, "shop-001",
                "New title", "Black / L", price);
    }
}

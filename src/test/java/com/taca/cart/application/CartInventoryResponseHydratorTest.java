package com.taca.cart.application;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CartInventoryResponseHydratorTest {
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");
    private final CartProductResponseHydrator productHydrator =
            new CartProductResponseHydrator();
    private final CartInventoryResponseHydrator inventoryHydrator =
            new CartInventoryResponseHydrator();

    @Test
    void shouldMarkAvailableWhenInventoryHasEnoughQuantity() {
        CartResponse response = hydrate(stock("sku-001", 7, "ACTIVE"));

        assertEquals(AvailabilityStatus.AVAILABLE,
                response.items().getFirst().availabilityStatus());
        assertTrue(response.items().getFirst().available());
    }

    @Test
    void shouldMarkOutOfStockWhenQuantityIsInsufficient() {
        CartResponse response = hydrate(stock("sku-001", 1, "ACTIVE"));

        assertEquals(AvailabilityStatus.OUT_OF_STOCK,
                response.items().getFirst().availabilityStatus());
        assertFalse(response.items().getFirst().available());
    }

    @Test
    void shouldMarkAvailableWhenQuantityExactlyMatches() {
        CartResponse response = hydrate(stock("sku-001", 2, "ACTIVE"));

        assertEquals(AvailabilityStatus.AVAILABLE,
                response.items().getFirst().availabilityStatus());
        assertTrue(response.items().getFirst().available());
    }

    @Test
    void shouldMarkOutOfStockWhenInventoryIsInactive() {
        CartResponse response = hydrate(stock("sku-001", 100, "INACTIVE"));

        assertEquals(AvailabilityStatus.OUT_OF_STOCK,
                response.items().getFirst().availabilityStatus());
        assertFalse(response.items().getFirst().available());
    }

    @Test
    void shouldKeepUnknownWhenInventoryIsUnavailable() {
        CartResponse response = inventoryHydrator.hydrate(
                productResponse(productFound()), productFound(), Map.of());

        assertEquals(AvailabilityStatus.UNKNOWN,
                response.items().getFirst().availabilityStatus());
        assertTrue(response.items().getFirst().available());
    }

    @Test
    void shouldNotOverrideProductUnavailable() {
        Map<String, ProductHydrationResult> products =
                Map.of("product-001", ProductHydrationResult.unavailable());

        CartResponse response = inventoryHydrator.hydrate(
                productResponse(products), products,
                Map.of("sku-001", stock("sku-001", 100, "ACTIVE")));

        assertEquals(AvailabilityStatus.PRODUCT_UNAVAILABLE,
                response.items().getFirst().availabilityStatus());
        assertFalse(response.items().getFirst().available());
    }

    @Test
    void shouldNotOverrideStaleProduct() {
        Map<String, ProductHydrationResult> products =
                Map.of("product-001", ProductHydrationResult.stale());

        CartResponse response = inventoryHydrator.hydrate(
                productResponse(products), products,
                Map.of("sku-001", stock("sku-001", 100, "ACTIVE")));

        assertEquals(AvailabilityStatus.STALE,
                response.items().getFirst().availabilityStatus());
    }

    @Test
    void shouldNotTrustInventoryWithoutProductVerification() {
        Cart cart = cart();
        CartResponse snapshot = CartResponse.fromSnapshot(Optional.of(cart));

        CartResponse response = inventoryHydrator.hydrate(
                snapshot, Map.of(),
                Map.of("sku-001", stock("sku-001", 100, "ACTIVE")));

        assertEquals(AvailabilityStatus.UNKNOWN,
                response.items().getFirst().availabilityStatus());
    }

    @Test
    void shouldKeepUnknownWhenInventoryStatusIsNotRecognized() {
        CartResponse response = hydrate(stock("sku-001", 100, "PENDING"));

        assertEquals(AvailabilityStatus.UNKNOWN,
                response.items().getFirst().availabilityStatus());
    }

    @Test
    void shouldNotChangeProductHydratedPriceOrDatabaseSnapshot() {
        Cart cart = cart();
        Map<String, ProductHydrationResult> products = productFound();

        CartResponse productResponse = productHydrator.hydrate(
                Optional.of(cart), products);
        CartResponse response = inventoryHydrator.hydrate(
                productResponse, products,
                Map.of("sku-001", stock("sku-001", 7, "ACTIVE")));

        assertEquals(199000L, response.items().getFirst().unitPriceSnapshot());
        assertEquals(398000L, response.items().getFirst().lineTotal());
        assertEquals(249000L, cart.items().getFirst().unitPriceSnapshot());
        assertEquals(3L, cart.version());
        assertEquals(2L, cart.items().getFirst().itemRevision());
        assertEquals(NOW, cart.updatedAt());
    }

    @Test
    void shouldPreserveCartMetadata() {
        CartResponse response = hydrate(stock("sku-001", 7, "ACTIVE"));

        assertEquals("cart-001", response.cartId());
        assertEquals(1, response.itemCount());
        assertEquals(NOW, response.updatedAt());
    }

    @Test
    void shouldRejectNegativeInventoryStock() {
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryStock("sku-001", -1, "ACTIVE", NOW));
    }

    private CartResponse hydrate(InventoryStock stock) {
        Map<String, ProductHydrationResult> products = productFound();
        return inventoryHydrator.hydrate(
                productResponse(products), products,
                Map.of(stock.skuId(), stock));
    }

    private CartResponse productResponse(
            Map<String, ProductHydrationResult> products) {
        return productHydrator.hydrate(Optional.of(cart()), products);
    }

    private Map<String, ProductHydrationResult> productFound() {
        ValidatedSkuSnapshot sku = new ValidatedSkuSnapshot(
                "product-001", "sku-001", "shop-001",
                "Current title", "Black / L", 199000L);

        return Map.of("product-001",
                ProductHydrationResult.found(Map.of("sku-001", sku)));
    }

    private InventoryStock stock(String skuId, long availableQty, String status) {
        return new InventoryStock(skuId, availableQty, status, NOW);
    }

    private Cart cart() {
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 2L, "Old title", "Old label",
                249000L, true, NOW, NOW);

        return new Cart("cart-001", "buyer-001", List.of(item), 3L, NOW, NOW);
    }
}

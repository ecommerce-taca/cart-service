package com.taca.cart.application;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartItemResponse;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.CartCasPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartQueryServiceTest {
    private static final String BUYER = "buyer-001";
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final CartCasPersistence persistence = mock(CartCasPersistence.class);
    private final CartHydrationOrchestrator orchestrator = mock(CartHydrationOrchestrator.class);
    private final CartQueryService service = new CartQueryService(persistence, orchestrator);

    @Test
    void shouldReturnEmptyCartWhenBuyerHasNoCart() {
        when(persistence.findByBuyerUserId(BUYER)).thenReturn(Optional.empty());
        when(orchestrator.hydrate(Optional.empty())).thenReturn(CartResponse.empty());

        CartResponse response = service.getCart(BUYER);

        assertNull(response.cartId());
        assertNull(response.updatedAt());
        assertTrue(response.items().isEmpty());
        assertEquals(0, response.itemCount());

        verify(persistence).findByBuyerUserId(BUYER);
        verify(orchestrator).hydrate(Optional.empty());
        verifyNoMoreInteractions(persistence, orchestrator);
    }

    @Test
    void shouldDelegateSnapshotToHydrationOrchestrator() {
        Cart cart = sampleCart();
        Optional<Cart> snapshot = Optional.of(cart);
        CartResponse expected = hydratedResponse(cart);

        when(persistence.findByBuyerUserId(BUYER)).thenReturn(snapshot);
        when(orchestrator.hydrate(snapshot)).thenReturn(expected);

        CartResponse response = service.getCart(BUYER);

        assertSame(expected, response);
        assertEquals("cart-001", response.cartId());
        assertEquals(1, response.itemCount());
        assertEquals(NOW, response.updatedAt());
        assertEquals("Current title", response.items().getFirst().titleSnapshot());
        assertEquals(229000L, response.items().getFirst().unitPriceSnapshot());
        assertEquals(458000L, response.items().getFirst().lineTotal());
        assertEquals(AvailabilityStatus.AVAILABLE,
                response.items().getFirst().availabilityStatus());

        verify(persistence).findByBuyerUserId(BUYER);
        verify(orchestrator).hydrate(snapshot);
        verifyNoMoreInteractions(persistence, orchestrator);
    }

    @Test
    void shouldPreserveOriginalCartSnapshot() {
        Cart cart = sampleCart();
        Cart before = new Cart(
                cart.id(), cart.buyerUserId(), cart.items(),
                cart.version(), cart.createdAt(), cart.updatedAt());

        when(persistence.findByBuyerUserId(BUYER)).thenReturn(Optional.of(cart));
        when(orchestrator.hydrate(Optional.of(cart)))
                .thenReturn(hydratedResponse(cart));

        CartResponse response = service.getCart(BUYER);

        assertEquals(before, cart);
        assertEquals(249000L, cart.items().getFirst().unitPriceSnapshot());
        assertEquals(5L, cart.version());
        assertEquals(3L, cart.items().getFirst().itemRevision());
        assertEquals(NOW, cart.updatedAt());
        assertEquals(229000L, response.items().getFirst().unitPriceSnapshot());

        verify(persistence).findByBuyerUserId(BUYER);
        verify(orchestrator).hydrate(Optional.of(cart));
        verifyNoMoreInteractions(persistence, orchestrator);
    }

    @Test
    void shouldQueryOnlyRequestedBuyer() {
        when(persistence.findByBuyerUserId("buyer-002")).thenReturn(Optional.empty());
        when(orchestrator.hydrate(Optional.empty())).thenReturn(CartResponse.empty());

        CartResponse response = service.getCart("buyer-002");

        assertNull(response.cartId());
        verify(persistence).findByBuyerUserId("buyer-002");
        verify(persistence, never()).findByBuyerUserId(BUYER);
        verify(orchestrator).hydrate(Optional.empty());
        verifyNoMoreInteractions(persistence, orchestrator);
    }

    @Test
    void shouldMapMongoFailureToStorageUnavailableWithoutHydration() {
        when(persistence.findByBuyerUserId(BUYER))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.getCart(BUYER));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
        verify(persistence).findByBuyerUserId(BUYER);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void shouldNotMisclassifyUnexpectedHydrationFailureAsMongoFailure() {
        Cart cart = sampleCart();
        when(persistence.findByBuyerUserId(BUYER)).thenReturn(Optional.of(cart));
        when(orchestrator.hydrate(Optional.of(cart)))
                .thenThrow(new IllegalStateException("Unexpected hydration failure"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getCart(BUYER));

        assertEquals("Unexpected hydration failure", ex.getMessage());
        verify(persistence).findByBuyerUserId(BUYER);
        verify(orchestrator).hydrate(Optional.of(cart));
    }

    private Cart sampleCart() {
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 3L, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW);

        return new Cart("cart-001", BUYER, List.of(item), 5L, NOW, NOW);
    }

    private CartResponse hydratedResponse(Cart cart) {
        CartItem item = cart.items().getFirst();
        CartItemResponse hydratedItem = new CartItemResponse(
                item.itemId(), item.productId(), item.skuId(), item.shopId(),
                "Current title", "Black / L", 229000L,
                item.quantity(), 458000L, true, AvailabilityStatus.AVAILABLE);

        return new CartResponse(cart.id(), List.of(hydratedItem), 1, cart.updatedAt());
    }
}

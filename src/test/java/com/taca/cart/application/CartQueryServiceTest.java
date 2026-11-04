package com.taca.cart.application;

import com.taca.cart.api.dto.AvailabilityStatus;
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
    private final CartQueryService service = new CartQueryService(persistence);

    @Test
    void shouldReturnEmptyCartWhenBuyerHasNoCart() {
        when(persistence.findByBuyerUserId(BUYER)).thenReturn(Optional.empty());

        CartResponse response = service.getCart(BUYER);

        assertNull(response.cartId());
        assertNull(response.updatedAt());
        assertTrue(response.items().isEmpty());
        assertEquals(0, response.itemCount());

        verify(persistence).findByBuyerUserId(BUYER);
        verifyNoMoreInteractions(persistence);
    }

    @Test
    void shouldReadCartSnapshotWithoutMutation() {
        Cart cart = sampleCart();
        when(persistence.findByBuyerUserId(BUYER)).thenReturn(Optional.of(cart));

        CartResponse response = service.getCart(BUYER);

        assertEquals("cart-001", response.cartId());
        assertEquals(1, response.itemCount());
        assertEquals(NOW, response.updatedAt());
        assertEquals("sku-001", response.items().getFirst().skuId());
        assertEquals(2, response.items().getFirst().quantity());
        assertEquals(498000L, response.items().getFirst().lineTotal());
        assertEquals(AvailabilityStatus.UNKNOWN,
                response.items().getFirst().availabilityStatus());

        assertEquals(5L, cart.version());
        assertEquals(3L, cart.items().getFirst().itemRevision());
        verify(persistence).findByBuyerUserId(BUYER);
        verifyNoMoreInteractions(persistence);
    }

    @Test
    void shouldQueryOnlyRequestedBuyer() {
        when(persistence.findByBuyerUserId("buyer-002")).thenReturn(Optional.empty());

        CartResponse response = service.getCart("buyer-002");

        assertNull(response.cartId());
        verify(persistence).findByBuyerUserId("buyer-002");
        verify(persistence, never()).findByBuyerUserId(BUYER);
        verifyNoMoreInteractions(persistence);
    }

    @Test
    void shouldMapMongoFailureToStorageUnavailable() {
        when(persistence.findByBuyerUserId(BUYER))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.getCart(BUYER));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
        verify(persistence).findByBuyerUserId(BUYER);
    }

    private Cart sampleCart() {
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 3L, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW
        );

        return new Cart("cart-001", BUYER, List.of(item), 5L, NOW, NOW);
    }
}

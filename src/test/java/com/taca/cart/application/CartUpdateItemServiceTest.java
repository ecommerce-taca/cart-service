package com.taca.cart.application;

import com.taca.cart.api.dto.UpdateCartItemResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartUpdateItemServiceTest {
    private static final String BUYER = "buyer-001";
    private static final String ITEM = "item-001";
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final CartMutationService mutationService = mock(CartMutationService.class);
    private final CartUpdateItemService service = new CartUpdateItemService(mutationService);

    @Test
    void shouldUpdateQuantityAndCalculateLineTotal() {
        when(mutationService.updateQuantity(BUYER, ITEM, 5)).thenReturn(cart(5));

        UpdateCartItemResponse result = service.updateQuantity(BUYER, ITEM, 5);

        assertEquals(ITEM, result.itemId());
        assertEquals(5, result.quantity());
        assertEquals(1245000L, result.lineTotal());
        verify(mutationService).updateQuantity(BUYER, ITEM, 5);
        verifyNoMoreInteractions(mutationService);
    }

    @Test
    void shouldReturnQuantityOne() {
        when(mutationService.updateQuantity(BUYER, ITEM, 1)).thenReturn(cart(1));

        UpdateCartItemResponse result = service.updateQuantity(BUYER, ITEM, 1);

        assertEquals(1, result.quantity());
        assertEquals(249000L, result.lineTotal());
    }

    @Test
    void shouldReturnQuantity999() {
        when(mutationService.updateQuantity(BUYER, ITEM, 999)).thenReturn(cart(999));

        UpdateCartItemResponse result = service.updateQuantity(BUYER, ITEM, 999);

        assertEquals(999, result.quantity());
        assertEquals(248751000L, result.lineTotal());
    }

    @Test
    void shouldUseAuthenticatedBuyerForMutation() {
        when(mutationService.updateQuantity("buyer-002", ITEM, 3)).thenReturn(cart(3));

        service.updateQuantity("buyer-002", ITEM, 3);

        verify(mutationService).updateQuantity("buyer-002", ITEM, 3);
        verify(mutationService, never()).updateQuantity(eq(BUYER), anyString(), anyInt());
    }

    @Test
    void shouldPreserveItemNotFound() {
        when(mutationService.updateQuantity(BUYER, ITEM, 3))
                .thenThrow(new CartException(
                        CartErrorCode.CART_ITEM_NOT_FOUND, "Cart item not found"));

        CartException ex = assertThrows(CartException.class,
                () -> service.updateQuantity(BUYER, ITEM, 3));

        assertEquals(CartErrorCode.CART_ITEM_NOT_FOUND, ex.getCode());
    }

    @Test
    void shouldPreserveVersionConflict() {
        when(mutationService.updateQuantity(BUYER, ITEM, 3))
                .thenThrow(new CartException(
                        CartErrorCode.CART_VERSION_CONFLICT, "Cart was modified concurrently"));

        CartException ex = assertThrows(CartException.class,
                () -> service.updateQuantity(BUYER, ITEM, 3));

        assertEquals(CartErrorCode.CART_VERSION_CONFLICT, ex.getCode());
    }

    @Test
    void shouldMapMongoFailureToStorageUnavailable() {
        when(mutationService.updateQuantity(BUYER, ITEM, 3))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.updateQuantity(BUYER, ITEM, 3));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
    }

    private Cart cart(int quantity) {
        CartItem item = new CartItem(
                ITEM, "product-001", "sku-001", "shop-001",
                quantity, 2L, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW);

        return new Cart("cart-001", BUYER, List.of(item), 2L, NOW, NOW);
    }
}

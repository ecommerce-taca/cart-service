package com.taca.cart.application;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartRemoveItemServiceTest {
    private static final String BUYER = "buyer-001";
    private static final String ITEM = "item-001";
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final CartMutationService mutationService = mock(CartMutationService.class);
    private final CartRemoveItemService service = new CartRemoveItemService(mutationService);

    @Test
    void shouldRemoveItemSuccessfully() {
        when(mutationService.removeItem(BUYER, ITEM)).thenReturn(emptyCart());

        assertDoesNotThrow(() -> service.removeItem(BUYER, ITEM));

        verify(mutationService).removeItem(BUYER, ITEM);
        verifyNoMoreInteractions(mutationService);
    }

    @Test
    void shouldUseProvidedBuyerIdentity() {
        when(mutationService.removeItem("buyer-002", ITEM)).thenReturn(emptyCart());

        service.removeItem("buyer-002", ITEM);

        verify(mutationService).removeItem("buyer-002", ITEM);
        verify(mutationService, never()).removeItem(BUYER, ITEM);
    }

    @Test
    void shouldPreserveItemNotFound() {
        when(mutationService.removeItem(BUYER, ITEM))
                .thenThrow(new CartException(
                        CartErrorCode.CART_ITEM_NOT_FOUND,
                        "Cart item not found"));

        CartException ex = assertThrows(CartException.class,
                () -> service.removeItem(BUYER, ITEM));

        assertEquals(CartErrorCode.CART_ITEM_NOT_FOUND, ex.getCode());
    }

    @Test
    void shouldReturnNotFoundWhenDeletedTwice() {
        when(mutationService.removeItem(BUYER, ITEM))
                .thenReturn(emptyCart())
                .thenThrow(new CartException(
                        CartErrorCode.CART_ITEM_NOT_FOUND,
                        "Cart item not found"));

        assertDoesNotThrow(() -> service.removeItem(BUYER, ITEM));

        CartException ex = assertThrows(CartException.class,
                () -> service.removeItem(BUYER, ITEM));

        assertEquals(CartErrorCode.CART_ITEM_NOT_FOUND, ex.getCode());
        verify(mutationService, times(2)).removeItem(BUYER, ITEM);
    }

    @Test
    void shouldPreserveVersionConflict() {
        when(mutationService.removeItem(BUYER, ITEM))
                .thenThrow(new CartException(
                        CartErrorCode.CART_VERSION_CONFLICT,
                        "Cart was modified concurrently"));

        CartException ex = assertThrows(CartException.class,
                () -> service.removeItem(BUYER, ITEM));

        assertEquals(CartErrorCode.CART_VERSION_CONFLICT, ex.getCode());
    }

    @Test
    void shouldMapMongoFailureToStorageUnavailable() {
        when(mutationService.removeItem(BUYER, ITEM))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.removeItem(BUYER, ITEM));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
    }

    private Cart emptyCart() {
        return new Cart("cart-001", BUYER, List.of(), 2L, NOW, NOW);
    }
}
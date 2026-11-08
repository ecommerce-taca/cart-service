package com.taca.cart.application;

import com.taca.cart.api.dto.InternalCartResponse;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalCartQueryServiceTest {
    private static final String BUYER = "buyer-001";
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private final CartCasPersistence persistence = mock(CartCasPersistence.class);
    private final InternalCartQueryService service = new InternalCartQueryService(persistence);

    @Test
    void shouldReturnAllItemsInOriginalOrderWhenItemIdsAreAbsent() {
        Cart cart = sampleCart();
        when(persistence.findByBuyerUserId(BUYER)).thenReturn(Optional.of(cart));

        InternalCartResponse response = service.getCart(BUYER, null);

        assertEquals("cart-001", response.cartId());
        assertEquals(BUYER, response.buyerUserId());
        assertEquals(List.of("item-001", "item-002"),
                response.items().stream().map(item -> item.itemId()).toList());
        assertTrue(response.missingItemIds().isEmpty());
        assertEquals(3L, response.items().getFirst().itemRevision());
        assertEquals(249000L, response.items().getFirst().unitPriceSnapshot());

        verify(persistence).findByBuyerUserId(BUYER);
        verifyNoMoreInteractions(persistence);
    }

    @Test
    void shouldFilterItemsAndPreserveRequestedOrder() {
        when(persistence.findByBuyerUserId(BUYER))
                .thenReturn(Optional.of(sampleCart()));

        InternalCartResponse response = service.getCart(
                BUYER, "item-002,item-missing,item-001,item-002");

        assertEquals(List.of("item-002", "item-001"),
                response.items().stream().map(item -> item.itemId()).toList());
        assertEquals(List.of("item-missing"), response.missingItemIds());
        assertEquals(2L, response.items().getFirst().itemRevision());
        assertEquals(3L, response.items().get(1).itemRevision());
    }

    @Test
    void shouldReturnRequestedIdsAsMissingWhenCartDoesNotExist() {
        when(persistence.findByBuyerUserId(BUYER)).thenReturn(Optional.empty());

        InternalCartResponse response = service.getCart(
                BUYER, "item-001,item-002");

        assertNull(response.cartId());
        assertEquals(BUYER, response.buyerUserId());
        assertTrue(response.items().isEmpty());
        assertEquals(List.of("item-001", "item-002"), response.missingItemIds());

        verify(persistence).findByBuyerUserId(BUYER);
        verifyNoMoreInteractions(persistence);
    }

    @Test
    void shouldReturnEmptyCartWithoutSelection() {
        when(persistence.findByBuyerUserId(BUYER)).thenReturn(Optional.empty());

        InternalCartResponse response = service.getCart(BUYER, null);

        assertNull(response.cartId());
        assertTrue(response.items().isEmpty());
        assertTrue(response.missingItemIds().isEmpty());
    }

    @Test
    void shouldRejectBlankItemIdsWithoutReadingDatabase() {
        for (String input : List.of("", " ", ",", "item-001,", ",item-002",
                "item-001,,item-002")) {
            CartException ex = assertThrows(CartException.class,
                    () -> service.getCart(BUYER, input));

            assertEquals(CartErrorCode.CART_INVALID_INPUT, ex.getCode());
        }

        verifyNoInteractions(persistence);
    }

    @Test
    void shouldRejectMoreThanHundredRequestedIds() {
        String itemIds = IntStream.range(0, 101)
                .mapToObj(i -> "item-" + i)
                .collect(Collectors.joining(","));

        CartException ex = assertThrows(CartException.class,
                () -> service.getCart(BUYER, itemIds));

        assertEquals(CartErrorCode.CART_INVALID_INPUT, ex.getCode());
        verifyNoInteractions(persistence);
    }

    @Test
    void shouldRejectMalformedItemId() {
        CartException ex = assertThrows(CartException.class,
                () -> service.getCart(BUYER, "item-001,../admin"));

        assertEquals(CartErrorCode.CART_INVALID_INPUT, ex.getCode());
        verifyNoInteractions(persistence);
    }

    @Test
    void shouldReturnOnlyRequestedBuyersCart() {
        when(persistence.findByBuyerUserId("buyer-002"))
                .thenReturn(Optional.empty());

        InternalCartResponse response = service.getCart("buyer-002", null);

        assertEquals("buyer-002", response.buyerUserId());
        assertNull(response.cartId());
        verify(persistence).findByBuyerUserId("buyer-002");
        verify(persistence, never()).findByBuyerUserId(BUYER);
    }

    @Test
    void shouldMapMongoFailureToStorageUnavailable() {
        when(persistence.findByBuyerUserId(BUYER))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.getCart(BUYER, null));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
        verify(persistence).findByBuyerUserId(BUYER);
        verifyNoMoreInteractions(persistence);
    }

    private Cart sampleCart() {
        CartItem first = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 3L, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW);

        CartItem second = new CartItem(
                "item-002", "product-002", "sku-002", "shop-001",
                1, 2L, "White shirt", "White / M",
                199000L, true, NOW, NOW);

        return new Cart("cart-001", BUYER,
                List.of(first, second), 5L, NOW, NOW);
    }
}

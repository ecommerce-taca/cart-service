package com.taca.cart.application;

import com.taca.cart.api.dto.AddCartItemResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.ProductCatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartAddItemServiceTest {
    private static final String BUYER = "buyer-001";
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final ProductCatalogClient productClient = mock(ProductCatalogClient.class);
    private final CartMutationService mutationService = mock(CartMutationService.class);
    private final CartAddItemService service = new CartAddItemService(productClient, mutationService);

    @Test
    void shouldValidateSkuBeforeMutation() {
        ValidatedSkuSnapshot sku = sku();
        when(productClient.validateSku("product-001", "sku-001")).thenReturn(sku);
        when(mutationService.addItem(BUYER, sku, 2)).thenReturn(cart(2));

        AddCartItemResponse result = service.addItem(BUYER, "product-001", "sku-001", 2);

        assertEquals("cart-001", result.cartId());
        assertEquals("item-001", result.itemId());
        assertEquals(2, result.quantity());
        assertEquals(498000L, result.lineTotal());

        var order = inOrder(productClient, mutationService);
        order.verify(productClient).validateSku("product-001", "sku-001");
        order.verify(mutationService).addItem(BUYER, sku, 2);
        order.verifyNoMoreInteractions();
    }

    @Test
    void shouldReturnMergedQuantityInsteadOfRequestedQuantity() {
        when(productClient.validateSku("product-001", "sku-001")).thenReturn(sku());
        when(mutationService.addItem(BUYER, sku(), 2)).thenReturn(cart(5));

        AddCartItemResponse result = service.addItem(BUYER, "product-001", "sku-001", 2);

        assertEquals("item-001", result.itemId());
        assertEquals(5, result.quantity());
        assertEquals(1245000L, result.lineTotal());
    }

    @Test
    void shouldReturnCappedQuantityAt999() {
        when(productClient.validateSku("product-001", "sku-001")).thenReturn(sku());
        when(mutationService.addItem(BUYER, sku(), 10)).thenReturn(cart(999));

        AddCartItemResponse result = service.addItem(BUYER, "product-001", "sku-001", 10);

        assertEquals(999, result.quantity());
        assertEquals(248751000L, result.lineTotal());
    }

    @Test
    void shouldNotMutateWhenProductUnavailable() {
        when(productClient.validateSku("product-001", "sku-001"))
                .thenThrow(new CartException(
                        CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                        "Product or SKU is unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.addItem(BUYER, "product-001", "sku-001", 2));

        assertEquals(CartErrorCode.CART_PRODUCT_UNAVAILABLE, ex.getCode());
        verifyNoInteractions(mutationService);
    }

    @Test
    void shouldNotMutateWhenProductCatalogUnavailable() {
        when(productClient.validateSku("product-001", "sku-001"))
                .thenThrow(new CartException(
                        CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                        "Product Catalog is unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.addItem(BUYER, "product-001", "sku-001", 2));

        assertEquals(CartErrorCode.CART_DEPENDENCY_UNAVAILABLE, ex.getCode());
        verifyNoInteractions(mutationService);
    }

    @Test
    void shouldPreserveCartBusinessConflicts() {
        when(productClient.validateSku("product-001", "sku-001")).thenReturn(sku());
        when(mutationService.addItem(BUYER, sku(), 1))
                .thenThrow(new CartException(
                        CartErrorCode.CART_TOO_MANY_ITEMS,
                        "Cart cannot contain more than 100 items"));

        CartException ex = assertThrows(CartException.class,
                () -> service.addItem(BUYER, "product-001", "sku-001", 1));

        assertEquals(CartErrorCode.CART_TOO_MANY_ITEMS, ex.getCode());
    }

    @Test
    void shouldMapMongoFailureToStorageUnavailable() {
        when(productClient.validateSku("product-001", "sku-001")).thenReturn(sku());
        when(mutationService.addItem(BUYER, sku(), 2))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.addItem(BUYER, "product-001", "sku-001", 2));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
    }

    private ValidatedSkuSnapshot sku() {
        return new ValidatedSkuSnapshot(
                "product-001", "sku-001", "shop-001",
                "Cotton jacket", "Black / L", 249000L);
    }

    private Cart cart(int quantity) {
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                quantity, 1L, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW);

        return new Cart("cart-001", BUYER, List.of(item), 1L, NOW, NOW);
    }
}
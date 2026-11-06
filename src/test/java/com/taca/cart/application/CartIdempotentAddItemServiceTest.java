package com.taca.cart.application;

import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.CartIdempotencyId;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.integrations.ProductCatalogClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartIdempotentAddItemServiceTest {
    private static final String BUYER = "buyer-001";
    private static final String KEY = "add-item-001";
    private static final String REQUEST_ID = "req-new-001";

    private final CartRequestHasher hasher = new CartRequestHasher();
    private final CartIdempotencyLookupService lookupService =
            mock(CartIdempotencyLookupService.class);
    private final ProductCatalogClient productClient =
            mock(ProductCatalogClient.class);
    private final CartIdempotencyTransactionService transactionService =
            mock(CartIdempotencyTransactionService.class);

    private final CartIdempotentAddItemService service =
            new CartIdempotentAddItemService(
                    hasher, lookupService, productClient, transactionService);

    @Test
    void shouldReplayWithoutCallingProductOrTransaction() {
        AddCartItemRequest request = request(2);
        CartIdempotencyKey stored = record();
        when(lookupService.findReplay(BUYER, KEY, hasher.hash(request)))
                .thenReturn(Optional.of(stored));

        CartIdempotencyKey result =
                service.addItem(BUYER, KEY, request, REQUEST_ID);

        assertSame(stored, result);
        assertEquals("req-original-001",
                ((Map<?, ?>) result.response().get("meta")).get("request_id"));
        verifyNoInteractions(productClient, transactionService);
    }

    @Test
    void shouldValidateProductThenExecuteTransactionForNewKey() {
        AddCartItemRequest request = request(2);
        ValidatedSkuSnapshot sku = sku();
        CartIdempotencyKey committed = record();

        when(lookupService.findReplay(BUYER, KEY, hasher.hash(request)))
                .thenReturn(Optional.empty());
        when(productClient.validateSku("product-001", "sku-001"))
                .thenReturn(sku);
        when(transactionService.execute(BUYER, KEY, request, sku, REQUEST_ID))
                .thenReturn(committed);

        CartIdempotencyKey result =
                service.addItem(BUYER, KEY, request, REQUEST_ID);

        assertSame(committed, result);

        var order = inOrder(lookupService, productClient, transactionService);
        order.verify(lookupService).findReplay(BUYER, KEY, hasher.hash(request));
        order.verify(productClient).validateSku("product-001", "sku-001");
        order.verify(transactionService)
                .execute(BUYER, KEY, request, sku, REQUEST_ID);
        order.verifyNoMoreInteractions();
    }

    @Test
    void shouldPreserveIdempotencyConflictWithoutCallingProduct() {
        AddCartItemRequest request = request(3);
        when(lookupService.findReplay(BUYER, KEY, hasher.hash(request)))
                .thenThrow(new CartException(
                        CartErrorCode.CART_IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key was already used"));

        CartException ex = assertThrows(CartException.class,
                () -> service.addItem(BUYER, KEY, request, REQUEST_ID));

        assertEquals(CartErrorCode.CART_IDEMPOTENCY_CONFLICT, ex.getCode());
        verifyNoInteractions(productClient, transactionService);
    }

    @Test
    void shouldNotCallProductWhenLookupStorageFails() {
        AddCartItemRequest request = request(2);
        when(lookupService.findReplay(BUYER, KEY, hasher.hash(request)))
                .thenThrow(new CartException(
                        CartErrorCode.CART_STORAGE_UNAVAILABLE,
                        "Idempotency storage unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.addItem(BUYER, KEY, request, REQUEST_ID));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
        verifyNoInteractions(productClient, transactionService);
    }

    @Test
    void shouldNotStartTransactionWhenProductIsUnavailable() {
        AddCartItemRequest request = request(2);
        when(lookupService.findReplay(BUYER, KEY, hasher.hash(request)))
                .thenReturn(Optional.empty());
        when(productClient.validateSku("product-001", "sku-001"))
                .thenThrow(new CartException(
                        CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                        "Product or SKU is unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.addItem(BUYER, KEY, request, REQUEST_ID));

        assertEquals(CartErrorCode.CART_PRODUCT_UNAVAILABLE, ex.getCode());
        verifyNoInteractions(transactionService);
    }

    @Test
    void shouldPreserveTransactionStorageFailure() {
        AddCartItemRequest request = request(2);
        ValidatedSkuSnapshot sku = sku();
        when(lookupService.findReplay(BUYER, KEY, hasher.hash(request)))
                .thenReturn(Optional.empty());
        when(productClient.validateSku("product-001", "sku-001"))
                .thenReturn(sku);
        when(transactionService.execute(BUYER, KEY, request, sku, REQUEST_ID))
                .thenThrow(new CartException(
                        CartErrorCode.CART_STORAGE_UNAVAILABLE,
                        "Cart transaction failed"));

        CartException ex = assertThrows(CartException.class,
                () -> service.addItem(BUYER, KEY, request, REQUEST_ID));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
    }

    @Test
    void shouldUseBuyerSpecificLookup() {
        AddCartItemRequest request = request(2);
        when(lookupService.findReplay("buyer-002", KEY, hasher.hash(request)))
                .thenReturn(Optional.of(record()));

        service.addItem("buyer-002", KEY, request, REQUEST_ID);

        verify(lookupService).findReplay("buyer-002", KEY, hasher.hash(request));
        verify(lookupService, never())
                .findReplay(eq(BUYER), anyString(), anyString());
        verifyNoInteractions(productClient, transactionService);
    }

    private AddCartItemRequest request(int quantity) {
        return new AddCartItemRequest("product-001", "sku-001", quantity);
    }

    private ValidatedSkuSnapshot sku() {
        return new ValidatedSkuSnapshot(
                "product-001", "sku-001", "shop-001",
                "Cotton jacket", "Black / L", 249000L);
    }

    private CartIdempotencyKey record() {
        Instant createdAt = Instant.parse("2026-11-04T00:00:00Z");
        Map<String, Object> response = Map.of(
                "data", Map.of(
                        "cart_id", "cart-001",
                        "item_id", "item-001",
                        "quantity", 2,
                        "line_total", 498000L),
                "meta", Map.of("request_id", "req-original-001"));

        return new CartIdempotencyKey(
                new CartIdempotencyId(BUYER, KEY),
                "a".repeat(64), response, 201,
                createdAt.plusSeconds(86400), createdAt);
    }
}

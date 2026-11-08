package com.taca.cart.application;

import com.taca.cart.api.dto.CheckedOutCartRequest;
import com.taca.cart.api.dto.CheckedOutCartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.api.validation.CheckedOutCartRequestValidator;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.CartCasPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InternalCartCleanupServiceTest {
    private static final String BUYER = "buyer-001";
    private static final String CART_ID = "cart-001";
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private CartCasPersistence persistence;
    private InternalCartCleanupService service;
    private AtomicReference<Cart> stored;

    @BeforeEach
    void setUp() {
        persistence = mock(CartCasPersistence.class);
        stored = new AtomicReference<>();
        service = new InternalCartCleanupService(
                persistence, new CheckedOutCartRequestValidator());

        when(persistence.mutate(anyString(), any())).thenAnswer(invocation -> {
            String buyer = invocation.getArgument(0);
            Function<Optional<Cart>, Cart> mutation = invocation.getArgument(1);
            Cart before = stored.get();

            if (before == null || !before.buyerUserId().equals(buyer)) {
                return mutation.apply(Optional.empty());
            }

            Cart candidate = mutation.apply(Optional.of(before));
            if (before.items().equals(candidate.items())) {
                return before;
            }

            Cart updated = new Cart(
                    before.id(), before.buyerUserId(), candidate.items(),
                    before.version() + 1,
                    before.createdAt(), before.updatedAt().plusMillis(1));

            stored.set(updated);
            return updated;
        });
    }

    @Test
    void shouldRemoveMatchingItemsAndPreserveUnrelatedItem() {
        stored.set(sampleCart());

        CheckedOutCartResponse response = service.cleanup(BUYER, request(
                List.of("item-001", "item-002"),
                Map.of("item-001", 3L, "item-002", 1L)));

        assertEquals(CART_ID, response.cartId());
        assertEquals(List.of("item-001", "item-002"), response.removedItemIds());
        assertTrue(response.missingItemIds().isEmpty());
        assertTrue(response.skippedModifiedItemIds().isEmpty());
        assertEquals(1, response.remainingItemCount());
        assertEquals(List.of("item-003"),
                stored.get().items().stream().map(CartItem::itemId).toList());
        assertEquals(6L, stored.get().version());
        assertTrue(stored.get().updatedAt().isAfter(NOW));

        verify(persistence).mutate(eq(BUYER), any());
    }

    @Test
    void shouldClassifyRemovedMissingAndModifiedItems() {
        Cart original = sampleCart();
        CartItem changed = item("item-002", "sku-002", 5, 2L);

        stored.set(cart(List.of(
                original.items().get(0),
                changed,
                original.items().get(2))));

        CheckedOutCartResponse response = service.cleanup(BUYER, request(
                List.of("item-missing", "item-002", "item-001"),
                Map.of("item-missing", 1L, "item-002", 1L, "item-001", 3L)));

        assertEquals(List.of("item-001"), response.removedItemIds());
        assertEquals(List.of("item-missing"), response.missingItemIds());
        assertEquals(List.of("item-002"), response.skippedModifiedItemIds());
        assertEquals(2, response.remainingItemCount());

        assertEquals(List.of("item-002", "item-003"),
                stored.get().items().stream().map(CartItem::itemId).toList());
        assertEquals(5, stored.get().items().getFirst().quantity());
        assertEquals(2L, stored.get().items().getFirst().itemRevision());
        assertEquals(6L, stored.get().version());
    }

    @Test
    void shouldPreserveRequestOrderInCleanupResults() {
        stored.set(sampleCart());

        CheckedOutCartResponse response = service.cleanup(BUYER, request(
                List.of("item-missing-1", "item-003", "item-001",
                        "item-missing-2", "item-002"),
                Map.of(
                        "item-missing-1", 1L,
                        "item-003", 2L,
                        "item-001", 3L,
                        "item-missing-2", 1L,
                        "item-002", 1L)));

        assertEquals(List.of("item-003", "item-001", "item-002"),
                response.removedItemIds());
        assertEquals(List.of("item-missing-1", "item-missing-2"),
                response.missingItemIds());
        assertTrue(response.skippedModifiedItemIds().isEmpty());
        assertEquals(0, response.remainingItemCount());
        assertTrue(stored.get().items().isEmpty());
        assertEquals(6L, stored.get().version());
    }

    @Test
    void shouldNotWriteWhenOnlyRevisionMismatchExists() {
        Cart before = sampleCart();
        stored.set(before);

        CheckedOutCartResponse response = service.cleanup(BUYER, request(
                List.of("item-001"), Map.of("item-001", 2L)));

        assertTrue(response.removedItemIds().isEmpty());
        assertTrue(response.missingItemIds().isEmpty());
        assertEquals(List.of("item-001"),
                response.skippedModifiedItemIds());
        assertEquals(3, response.remainingItemCount());
        assertEquals(before, stored.get());
        assertEquals(5L, stored.get().version());
        assertEquals(NOW, stored.get().updatedAt());
    }

    @Test
    void shouldNotWriteWhenAllRequestedItemsAreMissing() {
        Cart before = sampleCart();
        stored.set(before);

        CheckedOutCartResponse response = service.cleanup(BUYER, request(
                List.of("item-404", "item-405"),
                Map.of("item-404", 1L, "item-405", 1L)));

        assertTrue(response.removedItemIds().isEmpty());
        assertEquals(List.of("item-404", "item-405"),
                response.missingItemIds());
        assertTrue(response.skippedModifiedItemIds().isEmpty());
        assertEquals(3, response.remainingItemCount());
        assertEquals(before, stored.get());
    }

    @Test
    void shouldBeSafeWhenCleanupIsRepeated() {
        stored.set(sampleCart());
        CheckedOutCartRequest request = request(
                List.of("item-001"), Map.of("item-001", 3L));

        CheckedOutCartResponse first = service.cleanup(BUYER, request);
        Cart afterFirst = stored.get();

        CheckedOutCartResponse second = service.cleanup(BUYER, request);

        assertEquals(List.of("item-001"), first.removedItemIds());
        assertTrue(second.removedItemIds().isEmpty());
        assertEquals(List.of("item-001"), second.missingItemIds());
        assertEquals(2, second.remainingItemCount());
        assertEquals(afterFirst, stored.get());
        assertEquals(6L, stored.get().version());

        verify(persistence, times(2)).mutate(eq(BUYER), any());
    }

    @Test
    void shouldNotRemoveNewItemWhenSkuIsReaddedAfterCleanup() {
        stored.set(sampleCart());
        CheckedOutCartRequest request = request(
                List.of("item-001"), Map.of("item-001", 3L));

        service.cleanup(BUYER, request);
        Cart afterCleanup = stored.get();

        CartItem readded = item("item-new-001", "sku-001", 1, 1L);
        List<CartItem> items = new ArrayList<>(afterCleanup.items());
        items.add(readded);

        Cart afterReadd = new Cart(
                afterCleanup.id(), BUYER, items,
                afterCleanup.version() + 1,
                afterCleanup.createdAt(),
                afterCleanup.updatedAt().plusMillis(1));

        stored.set(afterReadd);

        CheckedOutCartResponse replay = service.cleanup(BUYER, request);

        assertTrue(replay.removedItemIds().isEmpty());
        assertEquals(List.of("item-001"), replay.missingItemIds());
        assertEquals(3, replay.remainingItemCount());
        assertEquals(afterReadd, stored.get());

        assertTrue(stored.get().items().stream()
                .anyMatch(item -> item.itemId().equals("item-new-001")));
        assertEquals(1L, stored.get().items().getLast().itemRevision());
    }

    @Test
    void shouldReturn404WhenCartDoesNotExist() {
        CheckedOutCartRequest request = request(
                List.of("item-001"), Map.of("item-001", 3L));

        CartException ex = assertThrows(CartException.class,
                () -> service.cleanup(BUYER, request));

        assertEquals(CartErrorCode.CART_ITEM_NOT_FOUND, ex.getCode());
        assertNull(stored.get());
        verify(persistence).mutate(eq(BUYER), any());
    }

    @Test
    void shouldReturn404WhenCartIdDoesNotMatch() {
        Cart before = sampleCart();
        stored.set(before);

        CheckedOutCartRequest request = new CheckedOutCartRequest(
                "cart-other", "group-001",
                List.of("item-001"), Map.of("item-001", 3L));

        CartException ex = assertThrows(CartException.class,
                () -> service.cleanup(BUYER, request));

        assertEquals(CartErrorCode.CART_ITEM_NOT_FOUND, ex.getCode());
        assertEquals(before, stored.get());
    }

    @Test
    void shouldNotAccessAnotherBuyersCart() {
        Cart before = sampleCart();
        stored.set(before);

        CheckedOutCartRequest request = request(
                List.of("item-001"), Map.of("item-001", 3L));

        CartException ex = assertThrows(CartException.class,
                () -> service.cleanup("buyer-002", request));

        assertEquals(CartErrorCode.CART_ITEM_NOT_FOUND, ex.getCode());
        assertEquals(before, stored.get());
        verify(persistence).mutate(eq("buyer-002"), any());
        verify(persistence, never()).mutate(eq(BUYER), any());
    }

    @Test
    void shouldRejectInvalidRequestBeforeAccessingStorage() {
        CheckedOutCartRequest request = request(
                List.of("item-001", "item-002"),
                Map.of("item-001", 3L, "item-other", 1L));

        CartException ex = assertThrows(CartException.class,
                () -> service.cleanup(BUYER, request));

        assertEquals(CartErrorCode.CART_INVALID_INPUT, ex.getCode());
        verifyNoInteractions(persistence);
    }

    @Test
    void shouldRejectInvalidBuyerBeforeAccessingStorage() {
        CheckedOutCartRequest request = request(
                List.of("item-001"), Map.of("item-001", 3L));

        CartException ex = assertThrows(CartException.class,
                () -> service.cleanup("bad buyer", request));

        assertEquals(CartErrorCode.CART_INVALID_INPUT, ex.getCode());
        verifyNoInteractions(persistence);
    }

    @Test
    void shouldMapMongoStorageFailureTo503() {
        stored.set(sampleCart());

        doThrow(new DataAccessResourceFailureException("MongoDB unavailable"))
                .when(persistence).mutate(eq(BUYER), any());

        CartException ex = assertThrows(CartException.class,
                () -> service.cleanup(BUYER, request(
                        List.of("item-001"), Map.of("item-001", 3L))));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
    }

    @Test
    void shouldPreserveVersionConflictError() {
        stored.set(sampleCart());

        doThrow(new CartException(
                CartErrorCode.CART_VERSION_CONFLICT,
                "Cart was modified concurrently"))
                .when(persistence).mutate(eq(BUYER), any());

        CartException ex = assertThrows(CartException.class,
                () -> service.cleanup(BUYER, request(
                        List.of("item-001"), Map.of("item-001", 3L))));

        assertEquals(CartErrorCode.CART_VERSION_CONFLICT, ex.getCode());
    }

    @Test
    void shouldReclassifyModifiedItemAfterCasConflict() {
        Cart original = sampleCart();
        stored.set(original);

        doAnswer(invocation -> {
            Function<Optional<Cart>, Cart> mutation = invocation.getArgument(1);

            Cart staleCandidate = mutation.apply(Optional.of(original));
            assertEquals(2, staleCandidate.items().size());

            CartItem changed = item("item-001", "sku-001", 5, 4L);
            Cart latest = new Cart(
                    CART_ID, BUYER,
                    List.of(changed, original.items().get(1),
                            original.items().get(2)),
                    6L, NOW, NOW.plusMillis(1));

            stored.set(latest);

            Cart retriedCandidate = mutation.apply(Optional.of(latest));
            assertEquals(latest.items(), retriedCandidate.items());

            return latest;
        }).when(persistence).mutate(eq(BUYER), any());

        CheckedOutCartResponse response = service.cleanup(BUYER, request(
                List.of("item-001"), Map.of("item-001", 3L)));

        assertTrue(response.removedItemIds().isEmpty());
        assertEquals(List.of("item-001"),
                response.skippedModifiedItemIds());
        assertEquals(3, response.remainingItemCount());
        assertEquals(5, stored.get().items().getFirst().quantity());
        assertEquals(4L, stored.get().items().getFirst().itemRevision());
        assertEquals(6L, stored.get().version());
    }

    @Test
    void shouldPreserveNewlyAddedItemDuringCasRetry() {
        Cart original = sampleCart();
        stored.set(original);

        doAnswer(invocation -> {
            Function<Optional<Cart>, Cart> mutation = invocation.getArgument(1);

            Cart staleCandidate = mutation.apply(Optional.of(original));
            assertEquals(2, staleCandidate.items().size());

            List<CartItem> latestItems = new ArrayList<>(original.items());
            latestItems.add(item("item-004", "sku-004", 1, 1L));

            Cart latest = new Cart(
                    CART_ID, BUYER, latestItems,
                    6L, NOW, NOW.plusMillis(1));

            stored.set(latest);

            Cart candidate = mutation.apply(Optional.of(latest));
            Cart committed = new Cart(
                    CART_ID, BUYER, candidate.items(),
                    7L, NOW, NOW.plusMillis(2));

            stored.set(committed);
            return committed;
        }).when(persistence).mutate(eq(BUYER), any());

        CheckedOutCartResponse response = service.cleanup(BUYER, request(
                List.of("item-001"), Map.of("item-001", 3L)));

        assertEquals(List.of("item-001"), response.removedItemIds());
        assertEquals(3, response.remainingItemCount());
        assertEquals(7L, stored.get().version());

        assertEquals(List.of("item-002", "item-003", "item-004"),
                stored.get().items().stream()
                        .map(CartItem::itemId).toList());
    }

    private CheckedOutCartRequest request(
            List<String> itemIds, Map<String, Long> revisions) {
        return new CheckedOutCartRequest(
                CART_ID, "group-001", itemIds, revisions);
    }

    private Cart sampleCart() {
        return cart(List.of(
                item("item-001", "sku-001", 2, 3L),
                item("item-002", "sku-002", 1, 1L),
                item("item-003", "sku-003", 4, 2L)));
    }

    private Cart cart(List<CartItem> items) {
        return new Cart(CART_ID, BUYER, items, 5L, NOW, NOW);
    }

    private CartItem item(
            String itemId, String skuId, int quantity, long revision) {
        return new CartItem(
                itemId, "product-" + skuId, skuId, "shop-001",
                quantity, revision,
                "Stored title", "Stored label", 249000L, true, NOW, NOW);
    }
}

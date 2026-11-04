package com.taca.cart.application;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.CartCasPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CartMutationServiceTest {
    private static final String BUYER = "buyer-001";
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    private CartMutationService service;
    private CartCasPersistence persistence;
    private AtomicReference<Cart> stored;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        persistence = mock(CartCasPersistence.class);
        stored = new AtomicReference<>();
        service = new CartMutationService(
                persistence, new UuidV7Generator(clock), clock);

        when(persistence.mutate(eq(BUYER), any())).thenAnswer(invocation -> {
            Function<Optional<Cart>, Cart> mutation = invocation.getArgument(1);
            Cart before = stored.get();
            Cart candidate = mutation.apply(Optional.ofNullable(before));
            Cart after;

            if (before == null) {
                after = new Cart(candidate.id(), BUYER, candidate.items(),
                        1L, candidate.createdAt(), candidate.updatedAt());
            } else if (before.items().equals(candidate.items())) {
                after = before;
            } else {
                after = new Cart(before.id(), BUYER, candidate.items(),
                        before.version() + 1, before.createdAt(), NOW.plusMillis(before.version()));
            }

            stored.set(after);
            return after;
        });
    }

    @Test
    void shouldCreateCartAndNewItemWithUuidV7() {
        Cart cart = service.addItem(BUYER, sku("sku-001"), 2);
        CartItem item = cart.items().getFirst();

        assertEquals(1L, cart.version());
        assertEquals(2, item.quantity());
        assertEquals(1L, item.itemRevision());
        assertEquals(249000L, item.unitPriceSnapshot());
        assertEquals(NOW, item.addedAt());
        assertEquals(NOW, item.updatedAt());

        assertUuidV7(cart.id());
        assertUuidV7(item.itemId());
    }

    @Test
    void shouldMergeDuplicateSkuAndIncreaseRevision() {
        Cart first = service.addItem(BUYER, sku("sku-001"), 2);
        Cart second = service.addItem(BUYER, sku("sku-001"), 3);

        assertEquals(1, second.items().size());
        assertEquals(5, second.items().getFirst().quantity());
        assertEquals(2L, second.items().getFirst().itemRevision());
        assertEquals(first.items().getFirst().itemId(), second.items().getFirst().itemId());
        assertEquals(first.items().getFirst().addedAt(), second.items().getFirst().addedAt());
        assertTrue(second.items().getFirst().updatedAt()
                .isAfter(first.items().getFirst().updatedAt()));
        assertEquals(2L, second.version());
    }

    @Test
    void shouldCapQuantityAt999AndPreserveNoopState() {
        service.addItem(BUYER, sku("sku-001"), 998);
        Cart capped = service.addItem(BUYER, sku("sku-001"), 5);
        Cart noop = service.addItem(BUYER, sku("sku-001"), 1);

        assertEquals(999, capped.items().getFirst().quantity());
        assertEquals(2L, capped.items().getFirst().itemRevision());
        assertEquals(capped, noop);
        assertEquals(2L, noop.version());
    }

    @Test
    void shouldUpdateQuantityAndIgnoreNoopPut() {
        service.addItem(BUYER, sku("sku-001"), 2);
        String itemId = stored.get().items().getFirst().itemId();

        Cart updated = service.updateQuantity(BUYER, itemId, 5);
        Cart unchanged = service.updateQuantity(BUYER, itemId, 5);

        assertEquals(5, updated.items().getFirst().quantity());
        assertEquals(2L, updated.items().getFirst().itemRevision());
        assertEquals(2L, updated.version());
        assertEquals(updated, unchanged);
    }

    @Test
    void shouldRejectInvalidQuantity() {
        assertError(CartErrorCode.CART_INVALID_INPUT,
                () -> service.addItem(BUYER, sku("sku-001"), 0));
        assertError(CartErrorCode.CART_INVALID_INPUT,
                () -> service.addItem(BUYER, sku("sku-001"), 1000));
        assertError(CartErrorCode.CART_INVALID_INPUT,
                () -> service.updateQuantity(BUYER, "item-001", 0));
        assertError(CartErrorCode.CART_INVALID_INPUT,
                () -> service.updateQuantity(BUYER, "item-001", -1));

        verifyNoInteractions(persistence);
    }

    @Test
    void shouldRejectHundredAndFirstDistinctSku() {
        List<CartItem> items = IntStream.range(0, 100)
                .mapToObj(i -> new CartItem(
                        "item-" + i, "product-001", "sku-" + i, "shop-001",
                        1, 1L, "Product", "Default", 1000L, true, NOW, NOW))
                .toList();

        stored.set(new Cart("cart-001", BUYER, items, 1L, NOW, NOW));

        assertError(CartErrorCode.CART_TOO_MANY_ITEMS,
                () -> service.addItem(BUYER, sku("sku-new"), 1));

        assertEquals(100, stored.get().items().size());
        assertEquals(1L, stored.get().version());

        Cart existing = service.addItem(BUYER, sku("sku-0"), 1);
        assertEquals(100, existing.items().size());
        assertEquals(2, existing.items().getFirst().quantity());
    }

    @Test
    void shouldDeleteThenReaddWithNewItemId() {
        Cart first = service.addItem(BUYER, sku("sku-001"), 2);
        String originalId = first.items().getFirst().itemId();

        Cart removed = service.removeItem(BUYER, originalId);
        assertTrue(removed.items().isEmpty());

        Cart readded = service.addItem(BUYER, sku("sku-001"), 1);
        CartItem item = readded.items().getFirst();

        assertNotEquals(originalId, item.itemId());
        assertEquals(1L, item.itemRevision());
        assertEquals(3L, readded.version());
        assertUuidV7(item.itemId());
    }

    @Test
    void shouldReturnNotFoundForMissingItem() {
        assertError(CartErrorCode.CART_ITEM_NOT_FOUND,
                () -> service.updateQuantity(BUYER, "missing", 2));
        assertError(CartErrorCode.CART_ITEM_NOT_FOUND,
                () -> service.removeItem(BUYER, "missing"));

        service.addItem(BUYER, sku("sku-001"), 1);
        assertError(CartErrorCode.CART_ITEM_NOT_FOUND,
                () -> service.removeItem(BUYER, "other-item"));
    }

    @Test
    void shouldRejectInconsistentSkuReferences() {
        service.addItem(BUYER, sku("sku-001"), 2);

        var inconsistent = new ValidatedSkuSnapshot(
                "another-product", "sku-001", "shop-001",
                "Product", "Default", 1000L
        );

        assertError(CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                () -> service.addItem(BUYER, inconsistent, 1));

        assertEquals(2, stored.get().items().getFirst().quantity());
        assertEquals(1L, stored.get().version());
    }

    @Test
    void shouldNotCreateCartWhenDeletingFromMissingCart() {
        assertError(CartErrorCode.CART_ITEM_NOT_FOUND,
                () -> service.removeItem(BUYER, "item-001"));

        assertNull(stored.get());
    }

    @Test
    void shouldRemoveOnlyRequestedItem() {
        Cart first = service.addItem(BUYER, sku("sku-001"), 2);
        String firstItemId = first.items().getFirst().itemId();

        Cart second = service.addItem(BUYER, sku("sku-002"), 3);
        String secondItemId = second.items().get(1).itemId();

        Cart result = service.removeItem(BUYER, firstItemId);

        assertEquals(1, result.items().size());
        assertEquals(secondItemId, result.items().getFirst().itemId());
        assertEquals("sku-002", result.items().getFirst().skuId());
        assertEquals(3, result.items().getFirst().quantity());
        assertEquals(3L, result.version());
    }

    private ValidatedSkuSnapshot sku(String skuId) {
        return new ValidatedSkuSnapshot(
                "product-001", skuId, "shop-001",
                "Cotton jacket", "Black / L", 249000L
        );
    }

    private void assertUuidV7(String value) {
        UUID uuid = UUID.fromString(value);
        assertEquals(7, uuid.version());
        assertEquals(2, uuid.variant());
        assertEquals(NOW.toEpochMilli(), uuid.getMostSignificantBits() >>> 16);
    }

    private void assertError(CartErrorCode expected, Runnable action) {
        CartException exception = assertThrows(CartException.class, action::run);
        assertEquals(expected, exception.getCode());
    }
}

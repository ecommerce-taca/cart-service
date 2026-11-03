
package com.taca.cart.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CartDomainTest {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Test
    void shouldCreateCartAndKeepItemsImmutable() {
        List<CartItem> source = new ArrayList<>(List.of(item("i1", "sku1", 2, 1, 249000)));
        Cart cart = new Cart("cart1", "buyer1", source, 0, NOW, NOW);

        source.clear();
        assertEquals(1, cart.items().size());
        assertThrows(UnsupportedOperationException.class, () -> cart.items().clear());
    }

    @Test
    void shouldRejectDuplicateItemIdOrSku() {
        CartItem a = item("i1", "sku1", 2, 1, 1000);

        assertThrows(IllegalArgumentException.class,
                () -> cart(List.of(a, item("i2", "sku1", 1, 1, 2000))));
        assertThrows(IllegalArgumentException.class,
                () -> cart(List.of(a, item("i1", "sku2", 1, 1, 2000))));
    }

    @Test
    void shouldEnforceQuantityPriceAndItemRevision() {
        assertThrows(IllegalArgumentException.class, () -> item("i1", "sku1", 0, 1, 1000));
        assertThrows(IllegalArgumentException.class, () -> item("i1", "sku1", 1000, 1, 1000));
        assertThrows(IllegalArgumentException.class, () -> item("i1", "sku1", 1, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> item("i1", "sku1", 1, 1, -1));
        assertDoesNotThrow(() -> item("i1", "sku1", 999, 1, 0));
    }

    @Test
    void shouldAllowEmptyCartButRejectMoreThan100Items() {
        assertTrue(cart(List.of()).items().isEmpty());

        List<CartItem> items = IntStream.range(0, 101)
                .mapToObj(i -> item("item" + i, "sku" + i, 1, 1, 1000))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> cart(items));
    }

    @Test
    void shouldRejectNegativeVersionAndInvalidTime() {
        assertThrows(IllegalArgumentException.class,
                () -> new Cart("c", "b", List.of(), -1, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new Cart("c", "b", List.of(), 0, NOW, NOW.minusSeconds(1)));
    }

    @Test
    void shouldValidateIdempotencyIdentityAndPersistedResponse() {
        var id = new CartIdempotencyId("buyer1", "add-sku-001");
        Map<String, Object> response = Map.of(
                "data", Map.of("quantity", 2),
                "meta", Map.of("request_id", "req-001")
        );

        var record = new CartIdempotencyKey(
                id, "a".repeat(64), response, 201, NOW.plusSeconds(86400), NOW);

        assertEquals("req-001",
                ((Map<?, ?>) record.response().get("meta")).get("request_id"));

        assertThrows(UnsupportedOperationException.class, () -> record.response().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new CartIdempotencyId("buyer1", "bad key"));
        assertThrows(IllegalArgumentException.class,
                () -> new CartIdempotencyKey(id, "invalid", response, 201, NOW.plusSeconds(86400), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CartIdempotencyKey(id, "a".repeat(64), Map.of("data", Map.of()), 201, NOW.plusSeconds(86400), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CartIdempotencyKey(id, "a".repeat(64), response, 200, NOW.plusSeconds(86400), NOW));
    }

    private Cart cart(List<CartItem> items) {
        return new Cart("cart1", "buyer1", items, 0, NOW, NOW);
    }

    private CartItem item(String id, String sku, int quantity, long revision, long price) {
        return new CartItem(id, "product1", sku, "shop1", quantity, revision,
                "Cotton jacket", "Black / L", price, true, NOW, NOW);
    }
}

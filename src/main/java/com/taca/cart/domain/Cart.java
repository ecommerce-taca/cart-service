
package com.taca.cart.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Cart(
        String id, String buyerUserId, List<CartItem> items,
        long version, Instant createdAt, Instant updatedAt
) {
    public static final int MAX_ITEMS = 100;

    public Cart {
        if (id == null || id.isBlank() || buyerUserId == null || buyerUserId.isBlank()) {
            throw new IllegalArgumentException("Cart ID and buyer ID must not be blank");
        }

        items = List.copyOf(Objects.requireNonNull(items, "items"));

        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Cart exceeds 100 items");
        }

        if (version < 0) {
            throw new IllegalArgumentException("Version must not be negative");
        }

        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt precedes createdAt");
        }

        Set<String> itemIds = new HashSet<>();
        Set<String> skuIds = new HashSet<>();

        for (CartItem item : items) {
            if (!itemIds.add(item.itemId()) || !skuIds.add(item.skuId())) {
                throw new IllegalArgumentException("Duplicate item ID or SKU in cart");
            }
        }
    }
}


package com.taca.cart.domain;

import java.time.Instant;
import java.util.Objects;

public record CartItem(
        String itemId, String productId, String skuId, String shopId,
        int quantity, long itemRevision,
        String titleSnapshot, String skuLabelSnapshot,
        long unitPriceSnapshot, boolean available,
        Instant addedAt, Instant updatedAt
) {
    public CartItem {
        if (itemId == null || itemId.isBlank() || productId == null || productId.isBlank()
                || skuId == null || skuId.isBlank() || shopId == null || shopId.isBlank()) {
            throw new IllegalArgumentException("Item and reference IDs must not be blank");
        }
        if (quantity < 1 || quantity > 999) {
            throw new IllegalArgumentException("Quantity must be 1..999");
        }

        if (itemRevision < 1) {
            throw new IllegalArgumentException("Item revision must be positive");
        }

        if (unitPriceSnapshot < 0) {
            throw new IllegalArgumentException("Unit price must not be negative");
        }

        Objects.requireNonNull(titleSnapshot, "titleSnapshot");
        Objects.requireNonNull(skuLabelSnapshot, "skuLabelSnapshot");
        Objects.requireNonNull(addedAt, "addedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");

        if (updatedAt.isBefore(addedAt)) {
            throw new IllegalArgumentException("updatedAt precedes addedAt");
        }
    }
}

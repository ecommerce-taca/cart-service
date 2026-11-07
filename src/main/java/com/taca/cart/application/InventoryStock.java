package com.taca.cart.application;

import java.time.Instant;
import java.util.Objects;

public record InventoryStock(
        String skuId,
        long availableQty,
        String status,
        Instant asOf
) {
    public InventoryStock {
        if (skuId == null || skuId.isBlank() || availableQty < 0
                || status == null || status.isBlank()) {
            throw new IllegalArgumentException("Invalid inventory stock snapshot");
        }
        Objects.requireNonNull(asOf, "asOf");
    }
}

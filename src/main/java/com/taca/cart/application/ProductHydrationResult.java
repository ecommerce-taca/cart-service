package com.taca.cart.application;

import java.util.Map;
import java.util.Objects;

public record ProductHydrationResult(
        State state,
        Map<String, ValidatedSkuSnapshot> skus
) {
    public enum State {
        FOUND,
        UNAVAILABLE,
        STALE
    }

    public ProductHydrationResult {
        Objects.requireNonNull(state, "state");
        skus = Map.copyOf(Objects.requireNonNull(skus, "skus"));

        if (state != State.FOUND && !skus.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only FOUND results may contain SKU snapshots");
        }

        skus.forEach((skuId, sku) -> {
            if (!skuId.equals(sku.skuId())) {
                throw new IllegalArgumentException(
                        "SKU map key must match snapshot SKU ID");
            }
        });
    }

    public static ProductHydrationResult found(
            Map<String, ValidatedSkuSnapshot> skus) {
        return new ProductHydrationResult(State.FOUND, skus);
    }

    public static ProductHydrationResult unavailable() {
        return new ProductHydrationResult(State.UNAVAILABLE, Map.of());
    }

    public static ProductHydrationResult stale() {
        return new ProductHydrationResult(State.STALE, Map.of());
    }
}

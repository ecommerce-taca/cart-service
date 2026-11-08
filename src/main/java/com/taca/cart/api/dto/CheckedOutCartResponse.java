package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public record CheckedOutCartResponse(
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("removed_item_ids") List<String> removedItemIds,
        @JsonProperty("missing_item_ids") List<String> missingItemIds,
        @JsonProperty("skipped_modified_item_ids") List<String> skippedModifiedItemIds,
        @JsonProperty("remaining_item_count") int remainingItemCount
) {
    public CheckedOutCartResponse {
        Objects.requireNonNull(cartId, "cartId");
        removedItemIds = List.copyOf(removedItemIds);
        missingItemIds = List.copyOf(missingItemIds);
        skippedModifiedItemIds = List.copyOf(skippedModifiedItemIds);

        if (remainingItemCount < 0) {
            throw new IllegalArgumentException(
                    "remainingItemCount must not be negative");
        }
    }
}

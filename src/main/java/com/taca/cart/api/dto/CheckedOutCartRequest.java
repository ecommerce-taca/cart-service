package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CheckedOutCartRequest(
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("checkout_group_id") String checkoutGroupId,
        @JsonProperty("item_ids") List<String> itemIds,
        @JsonProperty("expected_item_revisions") Map<String, Long> expectedItemRevisions
) {
    public CheckedOutCartRequest {
        if (itemIds != null) {
            itemIds = Collections.unmodifiableList(new ArrayList<>(itemIds));
        }
        if (expectedItemRevisions != null) {
            expectedItemRevisions = Collections.unmodifiableMap(
                    new LinkedHashMap<>(expectedItemRevisions));
        }
    }
}

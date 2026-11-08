package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record InternalCartResponse(
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("buyer_user_id") String buyerUserId,
        @JsonProperty("items") List<InternalCartItemResponse> items,
        @JsonProperty("missing_item_ids") List<String> missingItemIds
) {
    public InternalCartResponse {
        items = List.copyOf(items);
        missingItemIds = List.copyOf(missingItemIds);
    }
}

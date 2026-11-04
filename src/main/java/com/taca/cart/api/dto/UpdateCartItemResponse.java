package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateCartItemResponse(
        @JsonProperty("item_id") String itemId,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("line_total") long lineTotal
) {}

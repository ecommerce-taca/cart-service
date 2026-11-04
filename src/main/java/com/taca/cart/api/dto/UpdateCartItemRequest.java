package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateCartItemRequest(
        @JsonProperty("quantity") @NotNull @Min(1) @Max(999) Integer quantity
) {}
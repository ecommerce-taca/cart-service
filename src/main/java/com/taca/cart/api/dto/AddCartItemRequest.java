package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(
        @JsonProperty("product_id") @NotBlank String productId,
        @JsonProperty("sku_id") @NotBlank String skuId,
        @JsonProperty("quantity") @NotNull @Min(1) @Max(999) Integer quantity
) {}

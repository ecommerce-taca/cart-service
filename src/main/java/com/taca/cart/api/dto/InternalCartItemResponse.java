package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.taca.cart.domain.CartItem;

public record InternalCartItemResponse(
        @JsonProperty("item_id") String itemId,
        @JsonProperty("product_id") String productId,
        @JsonProperty("sku_id") String skuId,
        @JsonProperty("shop_id") String shopId,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("item_revision") long itemRevision,
        @JsonProperty("unit_price_snapshot") long unitPriceSnapshot
) {
    public static InternalCartItemResponse fromSnapshot(CartItem item) {
        return new InternalCartItemResponse(
                item.itemId(), item.productId(), item.skuId(),
                item.shopId(), item.quantity(), item.itemRevision(),
                item.unitPriceSnapshot());
    }
}

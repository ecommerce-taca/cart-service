package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.taca.cart.domain.CartItem;

public record CartItemResponse(
        @JsonProperty("item_id") String itemId,
        @JsonProperty("product_id") String productId,
        @JsonProperty("sku_id") String skuId,
        @JsonProperty("shop_id") String shopId,
        @JsonProperty("title_snapshot") String titleSnapshot,
        @JsonProperty("sku_label_snapshot") String skuLabelSnapshot,
        @JsonProperty("unit_price_snapshot") long unitPriceSnapshot,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("line_total") long lineTotal,
        @JsonProperty("available") boolean available,
        @JsonProperty("availability_status") AvailabilityStatus availabilityStatus
) {
    public static CartItemResponse fromSnapshot(CartItem item) {
        return new CartItemResponse(
                item.itemId(), item.productId(), item.skuId(), item.shopId(),
                item.titleSnapshot(), item.skuLabelSnapshot(),
                item.unitPriceSnapshot(), item.quantity(),
                Math.multiplyExact(item.unitPriceSnapshot(), item.quantity()),
                item.available(), AvailabilityStatus.UNKNOWN
        );
    }
}

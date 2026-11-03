
package com.taca.cart.persistence.document;

import org.springframework.data.mongodb.core.mapping.Field;
import java.time.Instant;

public record CartItemDocument(
        @Field("item_id") String itemId,
        @Field("product_id") String productId,
        @Field("sku_id") String skuId,
        @Field("shop_id") String shopId,
        @Field("quantity") int quantity,
        @Field("item_revision") long itemRevision,
        @Field("title_snapshot") String titleSnapshot,
        @Field("sku_label_snapshot") String skuLabelSnapshot,
        @Field("unit_price_snapshot") long unitPriceSnapshot,
        @Field("available") boolean available,
        @Field("added_at") Instant addedAt,
        @Field("updated_at") Instant updatedAt
) {}

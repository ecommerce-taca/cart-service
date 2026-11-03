
package com.taca.cart.application;

public record ValidatedSkuSnapshot(
        String productId,
        String skuId,
        String shopId,
        String title,
        String skuLabel,
        long unitPrice
) {
    public ValidatedSkuSnapshot {
        if (productId == null || productId.isBlank()
                || skuId == null || skuId.isBlank()
                || shopId == null || shopId.isBlank()) {
            throw new IllegalArgumentException("Product, SKU and shop IDs are required");
        }
        if (title == null || title.isBlank() || title.length() > 256) {
            throw new IllegalArgumentException("Invalid product title");
        }
        if (skuLabel == null || skuLabel.length() > 128) {
            throw new IllegalArgumentException("Invalid SKU label");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("Unit price must not be negative");
        }
    }
}

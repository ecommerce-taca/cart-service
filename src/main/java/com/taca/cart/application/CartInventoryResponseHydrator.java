package com.taca.cart.application;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartItemResponse;
import com.taca.cart.api.dto.CartResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
public class CartInventoryResponseHydrator {
    public CartResponse hydrate(
            CartResponse productHydrated,
            Map<String, ProductHydrationResult> products,
            Map<String, InventoryStock> inventory) {
        Objects.requireNonNull(productHydrated, "productHydrated");
        Objects.requireNonNull(products, "products");
        Objects.requireNonNull(inventory, "inventory");

        var items = productHydrated.items().stream()
                .map(item -> hydrateItem(item, products, inventory))
                .toList();

        return new CartResponse(
                productHydrated.cartId(), items, items.size(),
                productHydrated.updatedAt());
    }

    private CartItemResponse hydrateItem(
            CartItemResponse item,
            Map<String, ProductHydrationResult> products,
            Map<String, InventoryStock> inventory) {
        if (item.availabilityStatus() != AvailabilityStatus.UNKNOWN) return item;

        ProductHydrationResult product = products.get(item.productId());
        if (product == null || product.state() != ProductHydrationResult.State.FOUND) {
            return item;
        }

        ValidatedSkuSnapshot sku = product.skus().get(item.skuId());
        if (sku == null || !sku.productId().equals(item.productId())
                || !sku.shopId().equals(item.shopId())) {
            return item;
        }

        InventoryStock stock = inventory.get(item.skuId());
        if (stock == null) return item;

        if ("ACTIVE".equals(stock.status())) {
            boolean available = stock.availableQty() >= item.quantity();
            return copy(item, available, available
                    ? AvailabilityStatus.AVAILABLE
                    : AvailabilityStatus.OUT_OF_STOCK);
        }

        if ("INACTIVE".equals(stock.status()) || "DISABLED".equals(stock.status())) {
            return copy(item, false, AvailabilityStatus.OUT_OF_STOCK);
        }

        return item;
    }

    private CartItemResponse copy(CartItemResponse original,
                                  boolean available, AvailabilityStatus status) {
        return new CartItemResponse(
                original.itemId(), original.productId(),
                original.skuId(), original.shopId(),
                original.titleSnapshot(), original.skuLabelSnapshot(),
                original.unitPriceSnapshot(), original.quantity(),
                original.lineTotal(), available, status);
    }
}

package com.taca.cart.application;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartItemResponse;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class CartProductResponseHydrator {
    public CartResponse hydrate(Optional<Cart> snapshot,
                                Map<String, ProductHydrationResult> products) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(products, "products");

        if (snapshot.isEmpty()) return CartResponse.empty();

        Cart cart = snapshot.orElseThrow();
        var items = cart.items().stream()
                .map(item -> hydrateItem(item, products.get(item.productId())))
                .toList();

        return new CartResponse(cart.id(), items, items.size(), cart.updatedAt());
    }

    private CartItemResponse hydrateItem(CartItem item,
                                         ProductHydrationResult product) {
        CartItemResponse original = CartItemResponse.fromSnapshot(item);

        if (product == null || product.state() == ProductHydrationResult.State.STALE) {
            return withStatus(original, original.available(), AvailabilityStatus.STALE);
        }

        if (product.state() == ProductHydrationResult.State.UNAVAILABLE) {
            return withStatus(original, false, AvailabilityStatus.PRODUCT_UNAVAILABLE);
        }

        ValidatedSkuSnapshot sku = product.skus().get(item.skuId());
        if (sku == null) {
            return withStatus(original, false, AvailabilityStatus.PRODUCT_UNAVAILABLE);
        }

        if (!item.productId().equals(sku.productId())
                || !item.skuId().equals(sku.skuId())
                || !item.shopId().equals(sku.shopId())) {
            return withStatus(original, original.available(), AvailabilityStatus.STALE);
        }

        return new CartItemResponse(
                original.itemId(), original.productId(),
                original.skuId(), original.shopId(),
                sku.title(), sku.skuLabel(), sku.unitPrice(),
                original.quantity(),
                Math.multiplyExact(sku.unitPrice(), original.quantity()),
                original.available(), AvailabilityStatus.UNKNOWN);
    }

    private CartItemResponse withStatus(CartItemResponse original,
                                        boolean available, AvailabilityStatus status) {
        return new CartItemResponse(
                original.itemId(), original.productId(),
                original.skuId(), original.shopId(),
                original.titleSnapshot(), original.skuLabelSnapshot(),
                original.unitPriceSnapshot(), original.quantity(),
                original.lineTotal(), available, status);
    }
}

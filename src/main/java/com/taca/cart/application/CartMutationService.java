
package com.taca.cart.application;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.CartCasPersistence;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class CartMutationService {
    private static final int MIN_QTY = 1;
    private static final int MAX_QTY = 999;

    private final CartCasPersistence persistence;
    private final UuidV7Generator idGenerator;
    private final Clock clock;

    public CartMutationService(CartCasPersistence persistence,
                               UuidV7Generator idGenerator, Clock clock) {
        this.persistence = persistence;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public Cart addItem(String buyerUserId, ValidatedSkuSnapshot sku, int quantity) {
        validateQuantity(quantity);
        Objects.requireNonNull(sku, "sku");

        return persistence.mutate(buyerUserId, snapshot -> {
            Instant now = now();

            if (snapshot.isEmpty()) {
                CartItem item = newItem(sku, quantity, now);
                return new Cart(idGenerator.next(), buyerUserId,
                        List.of(item), 0L, now, now);
            }

            Cart current = snapshot.orElseThrow();

            for (int i = 0; i < current.items().size(); i++) {
                CartItem existing = current.items().get(i);
                if (!existing.skuId().equals(sku.skuId())) continue;

                if (!existing.productId().equals(sku.productId())
                        || !existing.shopId().equals(sku.shopId())) {
                    throw new CartException(
                            CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                            "SKU references do not match the existing cart item");
                }

                int newQuantity = Math.min(MAX_QTY, existing.quantity() + quantity);
                if (newQuantity == existing.quantity()) return current;

                CartItem updated = new CartItem(
                        existing.itemId(), existing.productId(),
                        existing.skuId(), existing.shopId(),
                        newQuantity, Math.addExact(existing.itemRevision(), 1L),
                        sku.title(), sku.skuLabel(), sku.unitPrice(),
                        true, existing.addedAt(), nextItemTime(existing.updatedAt())
                );

                return replaceItem(current, i, updated);
            }

            if (current.items().size() >= Cart.MAX_ITEMS) {
                throw new CartException(
                        CartErrorCode.CART_TOO_MANY_ITEMS,
                        "Cart cannot contain more than 100 items");
            }

            List<CartItem> items = new ArrayList<>(current.items());
            items.add(newItem(sku, quantity, now));
            return copyCart(current, items);
        });
    }

    public Cart updateQuantity(String buyerUserId, String itemId, int quantity) {
        validateQuantity(quantity);

        return persistence.mutate(buyerUserId, snapshot -> {
            Cart current = snapshot.orElseThrow(this::itemNotFound);

            for (int i = 0; i < current.items().size(); i++) {
                CartItem existing = current.items().get(i);
                if (!existing.itemId().equals(itemId)) continue;

                if (existing.quantity() == quantity) return current;

                CartItem updated = new CartItem(
                        existing.itemId(), existing.productId(),
                        existing.skuId(), existing.shopId(),
                        quantity, Math.addExact(existing.itemRevision(), 1L),
                        existing.titleSnapshot(), existing.skuLabelSnapshot(),
                        existing.unitPriceSnapshot(), existing.available(),
                        existing.addedAt(), nextItemTime(existing.updatedAt())
                );

                return replaceItem(current, i, updated);
            }

            throw itemNotFound();
        });
    }

    public Cart removeItem(String buyerUserId, String itemId) {
        return persistence.mutate(buyerUserId, snapshot -> {
            Cart current = snapshot.orElseThrow(this::itemNotFound);
            List<CartItem> items = new ArrayList<>(current.items());

            boolean removed = items.removeIf(item -> item.itemId().equals(itemId));
            if (!removed) throw itemNotFound();

            return copyCart(current, items);
        });
    }

    private CartItem newItem(ValidatedSkuSnapshot sku, int quantity, Instant now) {
        return new CartItem(
                idGenerator.next(), sku.productId(), sku.skuId(), sku.shopId(),
                quantity, 1L, sku.title(), sku.skuLabel(),
                sku.unitPrice(), true, now, now
        );
    }

    private Cart replaceItem(Cart current, int index, CartItem item) {
        List<CartItem> items = new ArrayList<>(current.items());
        items.set(index, item);
        return copyCart(current, items);
    }

    private Cart copyCart(Cart current, List<CartItem> items) {
        return new Cart(
                current.id(), current.buyerUserId(), items,
                current.version(), current.createdAt(), current.updatedAt()
        );
    }

    private void validateQuantity(int quantity) {
        if (quantity < MIN_QTY || quantity > MAX_QTY) {
            throw new CartException(
                    CartErrorCode.CART_INVALID_INPUT,
                    "Quantity must be between 1 and 999");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Instant nextItemTime(Instant previous) {
        Instant current = now();
        Instant minimum = previous.plusMillis(1);
        return current.isAfter(minimum) ? current : minimum;
    }

    private CartException itemNotFound() {
        return new CartException(
                CartErrorCode.CART_ITEM_NOT_FOUND,
                "Cart item not found");
    }
}

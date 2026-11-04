package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.taca.cart.domain.Cart;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CartResponse(
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("items") List<CartItemResponse> items,
        @JsonProperty("item_count") int itemCount,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public CartResponse {
        items = List.copyOf(items);
        if (itemCount != items.size()) {
            throw new IllegalArgumentException("Item count does not match items size");
        }
    }

    public static CartResponse empty() {
        return new CartResponse(null, List.of(), 0, null);
    }

    public static CartResponse fromSnapshot(Optional<Cart> snapshot) {
        if (snapshot.isEmpty()) return empty();

        Cart cart = snapshot.orElseThrow();
        List<CartItemResponse> items = cart.items().stream()
                .map(CartItemResponse::fromSnapshot)
                .toList();

        return new CartResponse(cart.id(), items, items.size(), cart.updatedAt());
    }
}
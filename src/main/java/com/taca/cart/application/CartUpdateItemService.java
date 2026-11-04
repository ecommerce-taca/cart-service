package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.dto.UpdateCartItemResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class CartUpdateItemService {
    private final CartMutationService mutationService;

    public CartUpdateItemService(CartMutationService mutationService) {
        this.mutationService = mutationService;
    }

    public UpdateCartItemResponse updateQuantity(String buyerUserId, String itemId, int quantity) {
        Cart cart;
        try {
            cart = mutationService.updateQuantity(buyerUserId, itemId, quantity);
        } catch (DataAccessException | MongoException ex) {
            throw new CartException(
                    CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart storage is unavailable");
        }

        CartItem item = cart.items().stream()
                .filter(candidate -> candidate.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Updated item not found in cart"));

        return new UpdateCartItemResponse(
                item.itemId(),
                item.quantity(),
                Math.multiplyExact(item.unitPriceSnapshot(), item.quantity()));
    }
}

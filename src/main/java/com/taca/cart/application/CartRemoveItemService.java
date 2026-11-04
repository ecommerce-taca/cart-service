package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class CartRemoveItemService {
    private final CartMutationService mutationService;

    public CartRemoveItemService(CartMutationService mutationService) {
        this.mutationService = mutationService;
    }

    public void removeItem(String buyerUserId, String itemId) {
        try {
            mutationService.removeItem(buyerUserId, itemId);
        } catch (DataAccessException | MongoException ex) {
            throw new CartException(
                    CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart storage is unavailable");
        }
    }
}

package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.persistence.CartCasPersistence;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class CartQueryService {
    private final CartCasPersistence persistence;

    public CartQueryService(CartCasPersistence persistence) {
        this.persistence = persistence;
    }

    public CartResponse getCart(String buyerUserId) {
        try {
            return CartResponse.fromSnapshot(
                    persistence.findByBuyerUserId(buyerUserId));
        } catch (DataAccessException | MongoException ex) {
            throw new CartException(
                    CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart storage is unavailable");
        }
    }
}

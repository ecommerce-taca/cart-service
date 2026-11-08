package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.persistence.CartCasPersistence;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class CartQueryService {
    private final CartCasPersistence persistence;
    private final CartHydrationOrchestrator orchestrator;

    public CartQueryService(CartCasPersistence persistence,
                            CartHydrationOrchestrator orchestrator) {
        this.persistence = Objects.requireNonNull(persistence);
        this.orchestrator = Objects.requireNonNull(orchestrator);
    }

    public CartResponse getCart(String buyerUserId) {
        Optional<Cart> snapshot;

        try {
            snapshot = persistence.findByBuyerUserId(buyerUserId);
        } catch (DataAccessException | MongoException ex) {
            throw new CartException(
                    CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart storage is unavailable");
        }

        return orchestrator.hydrate(snapshot);
    }
}

package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.dto.InternalCartItemResponse;
import com.taca.cart.api.dto.InternalCartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.CartCasPersistence;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class InternalCartQueryService {
    private static final int MAX_ITEM_IDS = 100;
    private final CartCasPersistence persistence;

    public InternalCartQueryService(CartCasPersistence persistence) {
        this.persistence = persistence;
    }

    public InternalCartResponse getCart(String buyerUserId, String rawItemIds) {
        if (buyerUserId == null
                || !buyerUserId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new CartException(CartErrorCode.CART_INVALID_INPUT,
                    "Invalid buyerUserId");
        }

        List<String> requestedIds = parseItemIds(rawItemIds);
        Optional<Cart> snapshot;

        try {
            snapshot = persistence.findByBuyerUserId(buyerUserId);
        } catch (DataAccessException | MongoException ex) {
            throw new CartException(CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart storage is unavailable");
        }

        if (snapshot.isEmpty()) {
            return new InternalCartResponse(null, buyerUserId, List.of(),
                    requestedIds == null ? List.of() : requestedIds);
        }

        Cart cart = snapshot.orElseThrow();

        if (requestedIds == null) {
            return new InternalCartResponse(cart.id(), buyerUserId,
                    cart.items().stream()
                            .map(InternalCartItemResponse::fromSnapshot)
                            .toList(), List.of());
        }

        Map<String, CartItem> byId = new HashMap<>();
        cart.items().forEach(item -> byId.put(item.itemId(), item));

        List<InternalCartItemResponse> selected = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String itemId : requestedIds) {
            CartItem item = byId.get(itemId);
            if (item == null) {
                missing.add(itemId);
            } else {
                selected.add(InternalCartItemResponse.fromSnapshot(item));
            }
        }

        return new InternalCartResponse(cart.id(), buyerUserId, selected, missing);
    }

    private List<String> parseItemIds(String rawItemIds) {
        if (rawItemIds == null) return null;

        String[] parts = rawItemIds.split(",", -1);
        if (parts.length > MAX_ITEM_IDS) throw invalidItemIds();

        LinkedHashSet<String> selected = new LinkedHashSet<>();

        for (String part : parts) {
            String id = part.trim();
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw invalidItemIds();
            }
            selected.add(id);
        }

        return List.copyOf(selected);
    }

    private CartException invalidItemIds() {
        return new CartException(CartErrorCode.CART_INVALID_INPUT,
                "item_ids must contain 1 to 100 valid item IDs");
    }
}

package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.dto.CheckedOutCartRequest;
import com.taca.cart.api.dto.CheckedOutCartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.api.validation.CheckedOutCartRequestValidator;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.CartCasPersistence;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class InternalCartCleanupService {
    private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    private final CartCasPersistence persistence;
    private final CheckedOutCartRequestValidator validator;

    public InternalCartCleanupService(
            CartCasPersistence persistence,
            CheckedOutCartRequestValidator validator) {
        this.persistence = persistence;
        this.validator = validator;
    }

    public CheckedOutCartResponse cleanup(
            String buyerUserId, CheckedOutCartRequest request) {
        if (buyerUserId == null || !buyerUserId.matches(ID_PATTERN)) {
            throw new CartException(CartErrorCode.CART_INVALID_INPUT,
                    "Invalid buyerUserId");
        }

        validator.validate(request);
        AtomicReference<CleanupDecision> acceptedDecision = new AtomicReference<>();
        Cart result;

        try {
            result = persistence.mutate(buyerUserId, snapshot -> {
                Cart current = snapshot.orElseThrow(this::cartNotFound);

                if (!current.id().equals(request.cartId())
                        || !current.buyerUserId().equals(buyerUserId)) {
                    throw cartNotFound();
                }

                CleanupDecision decision = classify(current, request);
                acceptedDecision.set(decision);

                if (decision.removed().isEmpty()) {
                    return current;
                }

                return new Cart(
                        current.id(),
                        current.buyerUserId(),
                        decision.remaining(),
                        current.version(),
                        current.createdAt(),
                        current.updatedAt());
            });
        } catch (DataAccessException | MongoException ex) {
            throw new CartException(CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart storage is unavailable");
        }

        CleanupDecision decision = acceptedDecision.get();
        if (decision == null) {
            throw new IllegalStateException("Cleanup decision was not produced");
        }

        return new CheckedOutCartResponse(
                result.id(),
                decision.removed(),
                decision.missing(),
                decision.skipped(),
                result.items().size());
    }

    private CleanupDecision classify(
            Cart current, CheckedOutCartRequest request) {
        Map<String, CartItem> existingItems = new HashMap<>();
        current.items().forEach(item ->
                existingItems.put(item.itemId(), item));

        List<String> removed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String itemId : request.itemIds()) {
            CartItem item = existingItems.get(itemId);

            if (item == null) {
                missing.add(itemId);
                continue;
            }

            long expectedRevision = request.expectedItemRevisions().get(itemId);

            if (item.itemRevision() != expectedRevision) {
                skipped.add(itemId);
                continue;
            }

            removed.add(itemId);
        }

        Set<String> removedIds = new HashSet<>(removed);
        List<CartItem> remaining = current.items().stream()
                .filter(item -> !removedIds.contains(item.itemId()))
                .toList();

        return new CleanupDecision(
                remaining, removed, missing, skipped);
    }

    private CartException cartNotFound() {
        return new CartException(CartErrorCode.CART_ITEM_NOT_FOUND,
                "Cart not found");
    }

    private record CleanupDecision(
            List<CartItem> remaining,
            List<String> removed,
            List<String> missing,
            List<String> skipped
    ) {
        private CleanupDecision {
            remaining = List.copyOf(remaining);
            removed = List.copyOf(removed);
            missing = List.copyOf(missing);
            skipped = List.copyOf(skipped);
        }
    }
}

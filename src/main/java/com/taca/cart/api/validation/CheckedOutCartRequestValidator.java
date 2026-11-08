package com.taca.cart.api.validation;

import com.taca.cart.api.dto.CheckedOutCartRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CheckedOutCartRequestValidator {
    private static final int MAX_ITEMS = 100;
    private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    public void validate(CheckedOutCartRequest request) {
        if (request == null) {
            throw invalid("Request body is required");
        }

        if (!validId(request.cartId())) {
            throw invalid("Invalid cart_id");
        }

        if (!validId(request.checkoutGroupId())) {
            throw invalid("Invalid checkout_group_id");
        }

        List<String> itemIds = request.itemIds();
        if (itemIds == null || itemIds.isEmpty() || itemIds.size() > MAX_ITEMS) {
            throw invalid("item_ids must contain 1 to 100 items");
        }

        Set<String> uniqueIds = new HashSet<>();

        for (String itemId : itemIds) {
            if (!validId(itemId)) {
                throw invalid("Invalid item_id");
            }

            if (!uniqueIds.add(itemId)) {
                throw invalid("Duplicate item_id");
            }
        }

        Map<String, Long> revisions = request.expectedItemRevisions();

        if (revisions == null || revisions.size() != uniqueIds.size()
                || !revisions.keySet().equals(uniqueIds)) {
            throw invalid("expected_item_revisions must match item_ids exactly");
        }

        for (Map.Entry<String, Long> entry : revisions.entrySet()) {
            if (!validId(entry.getKey()) || entry.getValue() == null
                    || entry.getValue() <= 0L) {
                throw invalid("expected_item_revisions must contain positive revisions");
            }
        }
    }

    private boolean validId(String value) {
        return value != null && value.matches(ID_PATTERN);
    }

    private CartException invalid(String message) {
        return new CartException(CartErrorCode.CART_INVALID_INPUT, message);
    }
}

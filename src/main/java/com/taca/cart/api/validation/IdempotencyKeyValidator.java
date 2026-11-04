package com.taca.cart.api.validation;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

public final class IdempotencyKeyValidator {
    private static final String HEADER = "Idempotency-Key";

    private IdempotencyKeyValidator() {}

    public static Optional<String> read(HttpServletRequest request) {
        var values = request.getHeaders(HEADER);
        if (!values.hasMoreElements()) return Optional.empty();

        String key = values.nextElement();
        if (values.hasMoreElements() || !valid(key)) {
            throw new CartException(CartErrorCode.CART_INVALID_INPUT, "Invalid Idempotency-Key");
        }

        return Optional.of(key);
    }

    private static boolean valid(String key) {
        if (key == null || key.isEmpty() || key.length() > 128) return false;

        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 33 || c > 126) return false;
        }

        return true;
    }
}

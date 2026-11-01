package com.taca.cart.api.error;

import java.util.Map;

public class CartException extends RuntimeException {
    private final CartErrorCode code;
    private final Map<String, String> details;

    public CartException(CartErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public CartException(CartErrorCode code, String message, Map<String, String> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public CartErrorCode getCode() {
        return code;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
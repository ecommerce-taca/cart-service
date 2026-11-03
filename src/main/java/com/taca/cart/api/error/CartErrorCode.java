package com.taca.cart.api.error;

import org.springframework.http.HttpStatus;

public enum CartErrorCode {
    CART_INVALID_INPUT(HttpStatus.BAD_REQUEST),
    CART_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    CART_FORBIDDEN(HttpStatus.FORBIDDEN),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND),
    CART_PRODUCT_UNAVAILABLE(HttpStatus.CONFLICT),
    CART_TOO_MANY_ITEMS(HttpStatus.CONFLICT),
    CART_VERSION_CONFLICT(HttpStatus.CONFLICT),
    CART_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    CART_DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    CART_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    CART_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    CartErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}

package com.taca.cart.domain;

public record CartIdempotencyId(String buyerUserId, String key) {
    public CartIdempotencyId {
        if (buyerUserId == null || buyerUserId.isBlank()) {
            throw new IllegalArgumentException("Buyer ID must not be blank");
        }
        if (key == null || !key.matches("[!-~]{1,128}")) {
            throw new IllegalArgumentException(
                    "Idempotency key must be 1..128 printable ASCII characters without spaces");
        }
    }
}

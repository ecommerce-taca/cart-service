package com.taca.cart.security;

public record ActorContext(String buyerUserId) {
    public ActorContext {
        if (buyerUserId == null || !buyerUserId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid buyer user ID");
        }
    }
}

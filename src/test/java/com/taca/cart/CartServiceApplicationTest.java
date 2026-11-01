package com.taca.cart;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CartServiceApplicationTest {
    @Test
    void shouldDeclareSpringBootApplication() {
        assertNotNull(CartServiceApplication.class.getAnnotation(SpringBootApplication.class));
    }
}

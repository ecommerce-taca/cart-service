package com.taca.cart.api;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.api.validation.IdempotencyKeyValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyKeyValidatorTest {
    @Test
    void shouldAllowMissingKey() {
        var request = new MockHttpServletRequest();
        assertTrue(IdempotencyKeyValidator.read(request).isEmpty());
    }

    @Test
    void shouldAcceptValidKey() {
        var request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", "add-item-001");

        assertEquals("add-item-001", IdempotencyKeyValidator.read(request).orElseThrow());
    }

    @Test
    void shouldRejectBlankAndInvalidKeys() {
        for (String key : new String[]{"", "bad key", "bad\tkey", "a".repeat(129)}) {
            var request = new MockHttpServletRequest();
            request.addHeader("Idempotency-Key", key);

            CartException ex = assertThrows(CartException.class,
                    () -> IdempotencyKeyValidator.read(request));

            assertEquals(CartErrorCode.CART_INVALID_INPUT, ex.getCode());
        }
    }

    @Test
    void shouldAcceptMaximumLength() {
        var request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", "a".repeat(128));

        assertEquals(128, IdempotencyKeyValidator.read(request).orElseThrow().length());
    }
    
    @Test
    void shouldRejectDuplicateHeaders() {
        var request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", "key-001");
        request.addHeader("Idempotency-Key", "key-002");

        CartException ex = assertThrows(CartException.class,
                () -> IdempotencyKeyValidator.read(request));

        assertEquals(CartErrorCode.CART_INVALID_INPUT, ex.getCode());
    }

}

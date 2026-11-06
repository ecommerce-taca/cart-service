package com.taca.cart.application;

import com.taca.cart.api.dto.AddCartItemRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CartRequestHasherTest {
    private final CartRequestHasher hasher = new CartRequestHasher();

    @Test
    void shouldGenerateExpectedCanonicalSha256() {
        var request = new AddCartItemRequest("product-001", "sku-001", 2);

        assertEquals(
                "9e2b91893b686009f588bf84989413f94dbe603f32d0e004dfc29fb25cf4bce8",
                hasher.hash(request));
    }

    @Test
    void shouldProduceSameHashForEquivalentRequests() {
        var first = new AddCartItemRequest("product-001", "sku-001", 2);
        var second = new AddCartItemRequest("product-001", "sku-001", 2);

        assertEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    void shouldProduceDifferentHashWhenQuantityChanges() {
        var first = new AddCartItemRequest("product-001", "sku-001", 2);
        var second = new AddCartItemRequest("product-001", "sku-001", 3);

        assertNotEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    void shouldProduceDifferentHashWhenSkuChanges() {
        var first = new AddCartItemRequest("product-001", "sku-001", 2);
        var second = new AddCartItemRequest("product-001", "sku-002", 2);

        assertNotEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    void shouldProduceDifferentHashWhenProductChanges() {
        var first = new AddCartItemRequest("product-001", "sku-001", 2);
        var second = new AddCartItemRequest("product-002", "sku-001", 2);

        assertNotEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    void shouldReturnLowercaseSha256Hex() {
        String hash = hasher.hash(
                new AddCartItemRequest("product-001", "sku-001", 2));

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldHandleSpecialCharactersDeterministically() {
        var request = new AddCartItemRequest(
                "product-\"special\"", "sku-\\special", 2);

        String first = hasher.hash(request);
        String second = hasher.hash(request);

        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> hasher.hash(null));
    }
}
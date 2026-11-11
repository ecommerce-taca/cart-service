package com.taca.cart.contracts;

import com.taca.cart.api.dto.*;
import com.taca.cart.api.error.CartErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class CartContractFixtureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST_ID = "req-001";

    @Test
    void buyerGetResponseShouldMatchContract() throws Exception {
        CartItemResponse item = new CartItemResponse(
                "item-001", "product-001", "sku-001", "shop-001",
                "Coffee", "250g", 120000L, 2, 240000L,
                true, AvailabilityStatus.AVAILABLE);

        CartResponse response = new CartResponse(
                "cart-001", List.of(item), 1,
                Instant.parse("2026-11-01T00:00:00Z"));

        assertFixture("buyer-get-response-v1.json",
                ApiResponse.of(response, REQUEST_ID));
    }

    @Test
    void buyerAddResponseShouldMatchContract() throws Exception {
        var response = new AddCartItemResponse(
                "cart-001", "item-001", 2, 240000L);

        assertFixture("buyer-add-response-v1.json",
                ApiResponse.of(response, REQUEST_ID));
    }

    @Test
    void buyerUpdateResponseShouldMatchContract() throws Exception {
        var response = new UpdateCartItemResponse(
                "item-001", 3, 360000L);

        assertFixture("buyer-update-response-v1.json",
                ApiResponse.of(response, REQUEST_ID));
    }

    @Test
    void internalGetResponseShouldMatchContract() throws Exception {
        var item = new InternalCartItemResponse(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 7L, 120000L);

        var response = new InternalCartResponse(
                "cart-001", "buyer-001", List.of(item),
                List.of("item-missing"));

        assertFixture("internal-get-response-v1.json",
                ApiResponse.of(response, REQUEST_ID));
    }

    @Test
    void checkedOutRequestShouldMatchContract() throws Exception {
        var request = new CheckedOutCartRequest(
                "cart-001", "group-001",
                List.of("item-001"), Map.of("item-001", 7L));

        assertFixture("checked-out-request-v1.json", request);

        var parsed = JSON.readValue(
                readFixture("checked-out-request-v1.json"),
                CheckedOutCartRequest.class);

        assertEquals(request, parsed);
    }

    @Test
    void checkedOutResponseShouldMatchContract() throws Exception {
        var response = new CheckedOutCartResponse(
                "cart-001",
                List.of("item-001"),
                List.of("item-missing"),
                List.of("item-modified"),
                1);

        assertFixture("checked-out-response-v1.json",
                ApiResponse.of(response, REQUEST_ID));
    }

    @Test
    void errorResponseShouldMatchContract() throws Exception {
        var error = new ApiErrorResponse.ApiError(
                "CART_STORAGE_UNAVAILABLE",
                "Cart storage is unavailable",
                Map.of(),
                "11223344556677889900aabbccddeeff");

        assertFixture("error-response-v1.json",
                new ApiErrorResponse(error));
    }

    @Test
    void buyerRequestDtosShouldUseSnakeCase() throws Exception {
        var addRequest = new AddCartItemRequest(
                "product-001", "sku-001", 2);
        var updateRequest = new UpdateCartItemRequest(3);

        assertEquals(
                JSON.readTree("""
                        {"product_id":"product-001","sku_id":"sku-001","quantity":2}
                        """),
                JSON.readTree(JSON.writeValueAsString(addRequest)));

        assertEquals(
                JSON.readTree("{\"quantity\":3}"),
                JSON.readTree(JSON.writeValueAsString(updateRequest)));
    }

    @Test
    void errorCodesShouldPreserveHttpStatus() {
        assertEquals(HttpStatus.BAD_REQUEST,
                CartErrorCode.CART_INVALID_INPUT.status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                CartErrorCode.CART_UNAUTHENTICATED.status());
        assertEquals(HttpStatus.FORBIDDEN,
                CartErrorCode.CART_FORBIDDEN.status());
        assertEquals(HttpStatus.NOT_FOUND,
                CartErrorCode.CART_ITEM_NOT_FOUND.status());
        assertEquals(HttpStatus.CONFLICT,
                CartErrorCode.CART_PRODUCT_UNAVAILABLE.status());
        assertEquals(HttpStatus.CONFLICT,
                CartErrorCode.CART_TOO_MANY_ITEMS.status());
        assertEquals(HttpStatus.CONFLICT,
                CartErrorCode.CART_VERSION_CONFLICT.status());
        assertEquals(HttpStatus.CONFLICT,
                CartErrorCode.CART_IDEMPOTENCY_CONFLICT.status());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                CartErrorCode.CART_DEPENDENCY_UNAVAILABLE.status());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                CartErrorCode.CART_STORAGE_UNAVAILABLE.status());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                CartErrorCode.CART_INTERNAL_ERROR.status());
    }

    private void assertFixture(String filename, Object value) throws Exception {
        JsonNode expected = JSON.readTree(readFixture(filename));
        JsonNode actual = JSON.readTree(JSON.writeValueAsString(value));

        assertEquals(expected, actual,
                "Contract drift detected in " + filename);
    }

    private String readFixture(String filename) throws Exception {
        String path = "contracts/cart/" + filename;

        try (InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(path),
                "Missing fixture: " + path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

package com.taca.cart.api;

import com.taca.cart.api.dto.*;
import com.taca.cart.api.error.GlobalExceptionHandler;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CartApiDtoTest {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldAcceptValidAddRequest() throws Exception {
        mvc.perform(post("/test/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_id":"product-001","sku_id":"sku-001","quantity":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_id").value("product-001"))
                .andExpect(jsonPath("$.data.sku_id").value("sku-001"))
                .andExpect(jsonPath("$.data.quantity").value(2));
    }

    @Test
    void shouldRejectInvalidAddQuantity() throws Exception {
        for (String quantity : List.of("0", "1000", "-1", "null")) {
            mvc.perform(post("/test/cart/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"product_id":"p1","sku_id":"s1","quantity":%s}
                                    """.formatted(quantity)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
        }
    }

    @Test
    void shouldRejectMissingOrBlankProductAndSku() throws Exception {
        for (String body : List.of(
                """
                {"sku_id":"s1","quantity":1}
                """,
                """
                {"product_id":"","sku_id":"s1","quantity":1}
                """,
                """
                {"product_id":"p1","sku_id":" ","quantity":1}
                """)) {
            mvc.perform(post("/test/cart/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
        }
    }

    @Test
    void shouldValidatePutQuantity() throws Exception {
        mvc.perform(put("/test/cart/items/item-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(999));

        mvc.perform(put("/test/cart/items/item-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
    }

    @Test
    void shouldRejectUnknownAndSpoofedJsonFields() {
        assertThrows(Exception.class, () -> JSON.readValue(
                """
                {"product_id":"p1","sku_id":"s1","quantity":1,"buyer_user_id":"admin"}
                """, AddCartItemRequest.class));

        assertThrows(Exception.class, () -> JSON.readValue(
                """
                {"product_id":"p1","sku_id":"s1","quantity":1,"user_id":"admin"}
                """, AddCartItemRequest.class));

        assertThrows(Exception.class, () -> JSON.readValue(
                """
                {"quantity":2,"unexpected_field":"value"}
                """, UpdateCartItemRequest.class));
    }

    @Test
    void shouldRejectFractionalQuantity() {
        assertThrows(Exception.class, () -> JSON.readValue(
                """
                {"product_id":"p1","sku_id":"s1","quantity":2.5}
                """, AddCartItemRequest.class));
    }

    @Test
    void shouldSerializeSuccessResponsesUsingSnakeCase() {
        var response = new AddCartItemResponse("cart-001", "item-001", 2, 498000L);
        String json = JSON.writeValueAsString(ApiResponse.of(response, "req-001"));

        var root = JSON.readTree(json);

        assertEquals("cart-001", root.path("data").path("cart_id").asString());
        assertEquals("item-001", root.path("data").path("item_id").asString());
        assertEquals(498000L, root.path("data").path("line_total").asLong());
        assertEquals("req-001", root.path("meta").path("request_id").asString());
    }

    @Test
    void shouldReturnEmptyCartWithNullFields() {
        String json = JSON.writeValueAsString(ApiResponse.of(CartResponse.empty(), "req-002"));
        var data = JSON.readTree(json).path("data");

        assertTrue(data.path("cart_id").isNull());
        assertTrue(data.path("updated_at").isNull());
        assertEquals(0, data.path("item_count").asInt());
        assertEquals(0, data.path("items").size());
    }

    @Test
    void shouldBuildCartResponseFromSnapshot() {
        Instant now = Instant.parse("2026-11-04T00:00:00Z");
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 1, "Cotton jacket", "Black / L",
                249000L, true, now, now
        );

        Cart cart = new Cart("cart-001", "buyer-001", List.of(item), 1, now, now);
        CartResponse response = CartResponse.fromSnapshot(Optional.of(cart));

        assertEquals("cart-001", response.cartId());
        assertEquals(1, response.itemCount());
        assertEquals(498000L, response.items().getFirst().lineTotal());
        assertEquals(AvailabilityStatus.UNKNOWN,
                response.items().getFirst().availabilityStatus());
        assertEquals(now, response.updatedAt());
    }

    @Test
    void shouldDetectLineTotalOverflow() {
        Instant now = Instant.parse("2026-11-04T00:00:00Z");
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 1, "Product", "Default",
                Long.MAX_VALUE, true, now, now
        );

        assertThrows(ArithmeticException.class,
                () -> CartItemResponse.fromSnapshot(item));
    }

    @RestController
    @RequestMapping("/test/cart/items")
    public static class ProbeController {
        @PostMapping
        public ApiResponse<AddCartItemRequest> add(@Valid @RequestBody AddCartItemRequest request) {
            return ApiResponse.of(request, "test-request");
        }

        @PutMapping("/{itemId}")
        public ApiResponse<UpdateCartItemRequest> update(
                @PathVariable String itemId,
                @Valid @RequestBody UpdateCartItemRequest request) {
            return ApiResponse.of(request, "test-request");
        }
    }
}

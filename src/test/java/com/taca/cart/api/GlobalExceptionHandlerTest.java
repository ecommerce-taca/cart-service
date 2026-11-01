package com.taca.cart.api;

import com.taca.cart.api.dto.ApiResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.api.error.GlobalExceptionHandler;
import com.taca.cart.observability.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void shouldReturnBusinessError() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_VERSION_CONFLICT"));
    }

    @Test
    void shouldReturnValidationError() throws Exception {
        mvc.perform(post("/test/quantity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        mvc.perform(post("/test/quantity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
    }

    @Test
    void shouldPreserveRequestId() throws Exception {
        mvc.perform(post("/test/quantity")
                        .header("X-Request-ID", "req-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", "req-123"))
                .andExpect(jsonPath("$.meta.request_id").value("req-123"));
    }

    @Test
    void shouldHideUnexpectedErrorDetails() throws Exception {
        mvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("CART_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {
        @GetMapping("/conflict")
        void conflict() {
            throw new CartException(CartErrorCode.CART_VERSION_CONFLICT, "Cart modified");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("Sensitive internal details");
        }

        @PostMapping("/quantity")
        ApiResponse<Map<String, Integer>> quantity(
                @Valid @RequestBody QuantityRequest body,
                HttpServletRequest request) {
            return ApiResponse.of(
                    Map.of("quantity", body.quantity()),
                    RequestIdFilter.resolve(request));
        }
    }

    record QuantityRequest(@Min(1) int quantity) {}
}
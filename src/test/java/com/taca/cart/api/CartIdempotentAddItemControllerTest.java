package com.taca.cart.api;

import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.api.dto.AddCartItemResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartAddItemService;
import com.taca.cart.application.CartIdempotentAddItemService;
import com.taca.cart.application.CartQueryService;
import com.taca.cart.application.CartRemoveItemService;
import com.taca.cart.application.CartUpdateItemService;
import com.taca.cart.domain.CartIdempotencyId;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.security.ActorContextResolver;
import com.taca.cart.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CartController.class)
@Import({SecurityConfig.class, ActorContextResolver.class})
class CartIdempotentAddItemControllerTest {
    private static final String PATH = "/api/v1/cart/items";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String KEY = "add-item-001";
    private static final String BODY = """
            {"product_id":"product-001","sku_id":"sku-001","quantity":2}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CartQueryService queryService;

    @MockitoBean
    private CartAddItemService addItemService;

    @MockitoBean
    private CartIdempotentAddItemService idempotentAddItemService;

    @MockitoBean
    private CartUpdateItemService updateItemService;

    @MockitoBean
    private CartRemoveItemService removeItemService;

    @DynamicPropertySource
    static void configureTokens(DynamicPropertyRegistry registry) {
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
    }

    @Test
    void shouldReturn201ForKeyedPost() throws Exception {
        when(idempotentAddItemService.addItem(
                "buyer-001", KEY, request(), "req-original-001"))
                .thenReturn(record());

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("Idempotency-Key", KEY)
                        .header("X-Request-ID", "req-original-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.cart_id").value("cart-001"))
                .andExpect(jsonPath("$.data.item_id").value("item-001"))
                .andExpect(jsonPath("$.data.quantity").value(2))
                .andExpect(jsonPath("$.data.line_total").value(498000))
                .andExpect(jsonPath("$.meta.request_id").value("req-original-001"));

        verify(idempotentAddItemService)
                .addItem("buyer-001", KEY, request(), "req-original-001");
        verifyNoInteractions(addItemService);
    }

    @Test
    void shouldPreserveOriginalResponseBodyOnReplay() throws Exception {
        when(idempotentAddItemService.addItem(
                "buyer-001", KEY, request(), "req-retry-002"))
                .thenReturn(record());

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("Idempotency-Key", KEY)
                        .header("X-Request-ID", "req-retry-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-ID", "req-retry-002"))
                .andExpect(jsonPath("$.meta.request_id").value("req-original-001"))
                .andExpect(jsonPath("$.data.quantity").value(2));

        verifyNoInteractions(addItemService);
    }

    @Test
    void shouldKeepPostWithoutKeyUnchanged() throws Exception {
        when(addItemService.addItem(
                "buyer-001", "product-001", "sku-001", 2))
                .thenReturn(new AddCartItemResponse(
                        "cart-001", "item-001", 2, 498000L));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("X-Request-ID", "req-no-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quantity").value(2))
                .andExpect(jsonPath("$.meta.request_id").value("req-no-key"));

        verify(addItemService)
                .addItem("buyer-001", "product-001", "sku-001", 2);
        verifyNoInteractions(idempotentAddItemService);
    }

    @Test
    void shouldRejectInvalidIdempotencyKey() throws Exception {
        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("Idempotency-Key", "invalid key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));

        verifyNoInteractions(idempotentAddItemService, addItemService);
    }

    @Test
    void shouldRejectDuplicateIdempotencyKeyHeaders() throws Exception {
        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("Idempotency-Key", KEY, "another-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));

        verifyNoInteractions(idempotentAddItemService, addItemService);
    }

    @Test
    void shouldReturn409ForDifferentBodyWithSameKey() throws Exception {
        when(idempotentAddItemService.addItem(
                "buyer-001", KEY, request(), "req-conflict"))
                .thenThrow(new CartException(
                        CartErrorCode.CART_IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key was already used"));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("Idempotency-Key", KEY)
                        .header("X-Request-ID", "req-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_IDEMPOTENCY_CONFLICT"));

        verifyNoInteractions(addItemService);
    }

    @Test
    void shouldReturn503ForTransactionStorageFailure() throws Exception {
        when(idempotentAddItemService.addItem(
                "buyer-001", KEY, request(), "req-storage"))
                .thenThrow(new CartException(
                        CartErrorCode.CART_STORAGE_UNAVAILABLE,
                        "Cart transaction storage is unavailable"));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("Idempotency-Key", KEY)
                        .header("X-Request-ID", "req-storage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CART_STORAGE_UNAVAILABLE"));
    }

    @Test
    void shouldRejectUnauthenticatedBuyerBeforeApplicationService() throws Exception {
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", KEY)
                        .header("X-User-ID", "buyer-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(idempotentAddItemService, addItemService);
    }

    private AddCartItemRequest request() {
        return new AddCartItemRequest("product-001", "sku-001", 2);
    }

    private CartIdempotencyKey record() {
        Instant createdAt = Instant.parse("2026-11-04T00:00:00Z");

        return new CartIdempotencyKey(
                new CartIdempotencyId("buyer-001", KEY),
                "a".repeat(64),
                Map.of(
                        "data", Map.of(
                                "cart_id", "cart-001",
                                "item_id", "item-001",
                                "quantity", 2,
                                "line_total", 498000L),
                        "meta", Map.of("request_id", "req-original-001")),
                201, createdAt.plusSeconds(86400), createdAt);
    }

    private RequestPostProcessor gateway(String buyerUserId) {
        return request -> {
            request.addHeader("X-Gateway-Service-Token", GATEWAY_TOKEN);
            request.addHeader("X-User-ID", buyerUserId);
            return request;
        };
    }
}
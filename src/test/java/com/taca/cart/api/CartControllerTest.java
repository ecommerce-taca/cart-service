package com.taca.cart.api;

import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartAddItemService;
import com.taca.cart.application.CartQueryService;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.security.ActorContextResolver;
import com.taca.cart.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CartController.class)
@Import({SecurityConfig.class, ActorContextResolver.class})
class CartControllerTest {
    private static final String PATH = "/api/v1/cart";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CartQueryService queryService;

    @MockitoBean
    private CartAddItemService addItemService;

    @DynamicPropertySource
    static void configureTokens(DynamicPropertyRegistry registry) {
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
    }

    @Test
    void shouldReturnEmptyCartWithRequestId() throws Exception {
        when(queryService.getCart("buyer-001")).thenReturn(CartResponse.empty());

        mvc.perform(get(PATH).with(gateway("buyer-001"))
                        .header("X-Request-ID", "req-cart-001"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-cart-001"))
                .andExpect(jsonPath("$.data.cart_id").value(nullValue()))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.item_count").value(0))
                .andExpect(jsonPath("$.data.updated_at").value(nullValue()))
                .andExpect(jsonPath("$.meta.request_id").value("req-cart-001"));

        verify(queryService).getCart("buyer-001");
        verifyNoMoreInteractions(queryService);
    }

    @Test
    void shouldReturnStoredCartSnapshot() throws Exception {
        when(queryService.getCart("buyer-001")).thenReturn(sampleResponse());

        mvc.perform(get(PATH).with(gateway("buyer-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value("cart-001"))
                .andExpect(jsonPath("$.data.item_count").value(1))
                .andExpect(jsonPath("$.data.items[0].item_id").value("item-001"))
                .andExpect(jsonPath("$.data.items[0].product_id").value("product-001"))
                .andExpect(jsonPath("$.data.items[0].sku_id").value("sku-001"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot").value(249000))
                .andExpect(jsonPath("$.data.items[0].line_total").value(498000))
                .andExpect(jsonPath("$.data.items[0].availability_status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.updated_at").value("2026-11-04T00:00:00Z"))
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].item_revision").doesNotExist());
    }

    @Test
    void shouldRejectUnauthenticatedBuyer() throws Exception {
        mvc.perform(get(PATH).header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));

        verifyNoInteractions(queryService);
    }

    @Test
    void shouldRejectInternalTokenForBuyerRoute() throws Exception {
        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }

    @Test
    void shouldIgnoreSpoofedBuyerQueryParameter() throws Exception {
        when(queryService.getCart("buyer-001")).thenReturn(CartResponse.empty());

        mvc.perform(get(PATH).with(gateway("buyer-001"))
                        .param("buyer_user_id", "buyer-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_count").value(0));

        verify(queryService).getCart("buyer-001");
        verify(queryService, never()).getCart("buyer-002");
    }

    @Test
    void shouldReturn503WhenCartStorageIsUnavailable() throws Exception {
        when(queryService.getCart("buyer-001")).thenThrow(new CartException(
                CartErrorCode.CART_STORAGE_UNAVAILABLE,
                "Cart storage is unavailable"));

        mvc.perform(get(PATH).with(gateway("buyer-001")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CART_STORAGE_UNAVAILABLE"));
    }

    private RequestPostProcessor gateway(String buyerUserId) {
        return request -> {
            request.addHeader("X-Gateway-Service-Token", GATEWAY_TOKEN);
            request.addHeader("X-User-ID", buyerUserId);
            return request;
        };
    }

    private CartResponse sampleResponse() {
        Instant now = Instant.parse("2026-11-04T00:00:00Z");
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 1L, "Cotton jacket", "Black / L",
                249000L, true, now, now);

        Cart cart = new Cart("cart-001", "buyer-001", List.of(item), 1L, now, now);
        return CartResponse.fromSnapshot(Optional.of(cart));
    }
}
package com.taca.cart.api;

import com.taca.cart.api.dto.InternalCartItemResponse;
import com.taca.cart.api.dto.InternalCartResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.InternalCartQueryService;
import com.taca.cart.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InternalCartController.class)
@Import(SecurityConfig.class)
class InternalCartControllerTest {
    private static final String PATH = "/api/v1/internal/carts/buyer-001";
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String GATEWAY_TOKEN = "a".repeat(64);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InternalCartQueryService queryService;

    @DynamicPropertySource
    static void configureTokens(DynamicPropertyRegistry registry) {
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
    }

    @Test
    void shouldReturnSelectedItemsWithInternalToken() throws Exception {
        InternalCartItemResponse item = new InternalCartItemResponse(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 3L, 249000L);

        when(queryService.getCart("buyer-001", "item-001,item-404"))
                .thenReturn(new InternalCartResponse(
                        "cart-001", "buyer-001",
                        List.of(item), List.of("item-404")));

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .header("X-Request-ID", "req-internal-001")
                        .param("item_ids", "item-001,item-404"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.request_id").value("req-internal-001"))
                .andExpect(jsonPath("$.data.cart_id").value("cart-001"))
                .andExpect(jsonPath("$.data.buyer_user_id").value("buyer-001"))
                .andExpect(jsonPath("$.data.items[0].item_id").value("item-001"))
                .andExpect(jsonPath("$.data.items[0].item_revision").value(3))
                .andExpect(jsonPath("$.data.items[0].unit_price_snapshot").value(249000))
                .andExpect(jsonPath("$.data.missing_item_ids[0]").value("item-404"));

        verify(queryService).getCart("buyer-001", "item-001,item-404");
        verifyNoMoreInteractions(queryService);
    }

    @Test
    void shouldReturnAllItemsWhenQueryParameterIsAbsent() throws Exception {
        when(queryService.getCart("buyer-001", null))
                .thenReturn(new InternalCartResponse(
                        null, "buyer-001", List.of(), List.of()));

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cart_id").value(
                        org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.missing_item_ids").isEmpty());

        verify(queryService).getCart("buyer-001", null);
    }

    @Test
    void shouldRejectMissingInternalToken() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_UNAUTHENTICATED"));

        verifyNoInteractions(queryService);
    }

    @Test
    void shouldRejectInvalidInternalToken() throws Exception {
        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", "f".repeat(64)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }

    @Test
    void shouldNotAcceptBuyerGatewayTokenInsteadOfInternalToken()
            throws Exception {
        mvc.perform(get(PATH)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }

    @Test
    void shouldReturn400ForEmptyItemIds() throws Exception {
        when(queryService.getCart("buyer-001", ""))
                .thenThrow(new CartException(
                        CartErrorCode.CART_INVALID_INPUT,
                        "item_ids must contain 1 to 100 valid item IDs"));

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("item_ids", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_INVALID_INPUT"));
    }

    @Test
    void shouldReturn503OnStorageFailure() throws Exception {
        when(queryService.getCart("buyer-001", null))
                .thenThrow(new CartException(
                        CartErrorCode.CART_STORAGE_UNAVAILABLE,
                        "Cart storage is unavailable"));

        mvc.perform(get(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("CART_STORAGE_UNAVAILABLE"));
    }
}

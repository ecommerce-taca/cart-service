package com.taca.cart.api;

import com.taca.cart.api.dto.UpdateCartItemResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartAddItemService;
import com.taca.cart.application.CartQueryService;
import com.taca.cart.application.CartRemoveItemService;
import com.taca.cart.application.CartUpdateItemService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CartController.class)
@Import({SecurityConfig.class, ActorContextResolver.class})
class CartUpdateItemControllerTest {
    private static final String PATH = "/api/v1/cart/items/item-001";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CartQueryService queryService;

    @MockitoBean
    private CartAddItemService addItemService;

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
    void shouldUpdateQuantitySuccessfully() throws Exception {
        when(updateItemService.updateQuantity("buyer-001", "item-001", 5))
                .thenReturn(new UpdateCartItemResponse("item-001", 5, 1245000L));

        mvc.perform(put(PATH).with(gateway("buyer-001"))
                        .header("X-Request-ID", "req-update-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-update-001"))
                .andExpect(jsonPath("$.data.item_id").value("item-001"))
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andExpect(jsonPath("$.data.line_total").value(1245000))
                .andExpect(jsonPath("$.meta.request_id").value("req-update-001"));

        verify(updateItemService).updateQuantity("buyer-001", "item-001", 5);
        verifyNoMoreInteractions(updateItemService);
        verifyNoInteractions(queryService, addItemService);
    }

    @Test
    void shouldRejectUnauthenticatedBuyer() throws Exception {
        mvc.perform(put(PATH)
                        .header("X-User-ID", "buyer-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));

        verifyNoInteractions(updateItemService);
    }

    @Test
    void shouldRejectInternalTokenForBuyerRoute() throws Exception {
        mvc.perform(put(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(updateItemService);
    }

    @Test
    void shouldRejectInvalidQuantity() throws Exception {
        for (String quantity : new String[]{"0", "-1", "1000", "null"}) {
            mvc.perform(put(PATH).with(gateway("buyer-001"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":" + quantity + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
        }

        verifyNoInteractions(updateItemService);
    }

    @Test
    void shouldRejectMissingQuantity() throws Exception {
        mvc.perform(put(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));

        verifyNoInteractions(updateItemService);
    }

    @Test
    void shouldRejectFractionalQuantity() throws Exception {
        mvc.perform(put(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));

        verifyNoInteractions(updateItemService);
    }

    @Test
    void shouldRejectSpoofedBuyerBodyField() throws Exception {
        mvc.perform(put(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5,\"buyer_user_id\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));

        verifyNoInteractions(updateItemService);
    }

    @Test
    void shouldUseAuthenticatedBuyerIdentity() throws Exception {
        when(updateItemService.updateQuantity("buyer-001", "item-001", 3))
                .thenReturn(new UpdateCartItemResponse("item-001", 3, 747000L));

        mvc.perform(put(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isOk());

        verify(updateItemService).updateQuantity("buyer-001", "item-001", 3);
        verify(updateItemService, never())
                .updateQuantity(eq("buyer-002"), anyString(), anyInt());
    }

    @Test
    void shouldReturn404ForMissingOrForeignItem() throws Exception {
        when(updateItemService.updateQuantity("buyer-001", "item-001", 3))
                .thenThrow(new CartException(
                        CartErrorCode.CART_ITEM_NOT_FOUND, "Cart item not found"));

        mvc.perform(put(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));
    }

    @Test
    void shouldReturn409ForVersionConflict() throws Exception {
        when(updateItemService.updateQuantity("buyer-001", "item-001", 3))
                .thenThrow(new CartException(
                        CartErrorCode.CART_VERSION_CONFLICT,
                        "Cart was modified concurrently"));

        mvc.perform(put(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_VERSION_CONFLICT"));
    }

    @Test
    void shouldReturn503WhenStorageUnavailable() throws Exception {
        when(updateItemService.updateQuantity("buyer-001", "item-001", 3))
                .thenThrow(new CartException(
                        CartErrorCode.CART_STORAGE_UNAVAILABLE,
                        "Cart storage is unavailable"));

        mvc.perform(put(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
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
}

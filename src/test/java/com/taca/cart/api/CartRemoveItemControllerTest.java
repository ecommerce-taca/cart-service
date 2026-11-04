package com.taca.cart.api;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CartController.class)
@Import({SecurityConfig.class, ActorContextResolver.class})
class CartRemoveItemControllerTest {
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
    void shouldDeleteItemAndReturn204WithoutBody() throws Exception {
        mvc.perform(delete(PATH).with(gateway("buyer-001"))
                        .header("X-Request-ID", "req-delete-001"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-delete-001"))
                .andExpect(content().string(""));

        verify(removeItemService).removeItem("buyer-001", "item-001");
        verifyNoMoreInteractions(removeItemService);
        verifyNoInteractions(queryService, addItemService, updateItemService);
    }

    @Test
    void shouldRejectUnauthenticatedBuyer() throws Exception {
        mvc.perform(delete(PATH)
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));

        verifyNoInteractions(removeItemService);
    }

    @Test
    void shouldRejectInternalTokenForBuyerRoute() throws Exception {
        mvc.perform(delete(PATH)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(removeItemService);
    }

    @Test
    void shouldUseAuthenticatedBuyerIdentity() throws Exception {
        mvc.perform(delete(PATH).with(gateway("buyer-001"))
                        .param("buyer_user_id", "buyer-002"))
                .andExpect(status().isNoContent());

        verify(removeItemService).removeItem("buyer-001", "item-001");
        verify(removeItemService, never()).removeItem("buyer-002", "item-001");
    }

    @Test
    void shouldReturn404ForMissingOrForeignItem() throws Exception {
        doThrow(new CartException(
                CartErrorCode.CART_ITEM_NOT_FOUND,
                "Cart item not found"))
                .when(removeItemService).removeItem("buyer-001", "item-001");

        mvc.perform(delete(PATH).with(gateway("buyer-001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));
    }

    @Test
    void shouldReturn404WhenDeletingAgain() throws Exception {
        doNothing()
                .doThrow(new CartException(
                        CartErrorCode.CART_ITEM_NOT_FOUND,
                        "Cart item not found"))
                .when(removeItemService).removeItem("buyer-001", "item-001");

        mvc.perform(delete(PATH).with(gateway("buyer-001")))
                .andExpect(status().isNoContent());

        mvc.perform(delete(PATH).with(gateway("buyer-001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));

        verify(removeItemService, times(2)).removeItem("buyer-001", "item-001");
    }

    @Test
    void shouldReturn409ForVersionConflict() throws Exception {
        doThrow(new CartException(
                CartErrorCode.CART_VERSION_CONFLICT,
                "Cart was modified concurrently"))
                .when(removeItemService).removeItem("buyer-001", "item-001");

        mvc.perform(delete(PATH).with(gateway("buyer-001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_VERSION_CONFLICT"));
    }

    @Test
    void shouldReturn503WhenStorageUnavailable() throws Exception {
        doThrow(new CartException(
                CartErrorCode.CART_STORAGE_UNAVAILABLE,
                "Cart storage is unavailable"))
                .when(removeItemService).removeItem("buyer-001", "item-001");

        mvc.perform(delete(PATH).with(gateway("buyer-001")))
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
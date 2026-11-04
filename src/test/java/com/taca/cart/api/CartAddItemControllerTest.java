package com.taca.cart.api;

import com.taca.cart.api.dto.AddCartItemResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.application.CartAddItemService;
import com.taca.cart.application.CartQueryService;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CartController.class)
@Import({SecurityConfig.class, ActorContextResolver.class})
class CartAddItemControllerTest {
    private static final String PATH = "/api/v1/cart/items";
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String VALID_BODY = """
            {"product_id":"product-001","sku_id":"sku-001","quantity":2}
            """;

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
    void shouldReturn201WithAddItemResponse() throws Exception {
        when(addItemService.addItem("buyer-001", "product-001", "sku-001", 2))
                .thenReturn(new AddCartItemResponse("cart-001", "item-001", 2, 498000L));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("X-Request-ID", "req-add-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "req-add-001"))
                .andExpect(jsonPath("$.data.cart_id").value("cart-001"))
                .andExpect(jsonPath("$.data.item_id").value("item-001"))
                .andExpect(jsonPath("$.data.quantity").value(2))
                .andExpect(jsonPath("$.data.line_total").value(498000))
                .andExpect(jsonPath("$.meta.request_id").value("req-add-001"));

        verify(addItemService).addItem("buyer-001", "product-001", "sku-001", 2);
        verifyNoMoreInteractions(addItemService);
        verifyNoInteractions(queryService);
    }

    @Test
    void shouldUseAuthenticatedBuyerIdentity() throws Exception {
        when(addItemService.addItem("buyer-001", "product-001", "sku-001", 2))
                .thenReturn(new AddCartItemResponse("cart-001", "item-001", 2, 498000L));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());

        verify(addItemService).addItem("buyer-001", "product-001", "sku-001", 2);
        verify(addItemService, never()).addItem(eq("buyer-002"), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldRejectRequestWithoutGatewayAuthentication() throws Exception {
        mvc.perform(post(PATH)
                        .header("X-User-ID", "buyer-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));

        verifyNoInteractions(addItemService);
    }

    @Test
    void shouldRejectInvalidQuantity() throws Exception {
        for (String quantity : new String[]{"0", "1000", "-1", "null", "2.5"}) {
            String body = """
                    {"product_id":"product-001","sku_id":"sku-001","quantity":%s}
                    """.formatted(quantity);

            mvc.perform(post(PATH).with(gateway("buyer-001"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
        }

        verifyNoInteractions(addItemService);
    }

    @Test
    void shouldRejectMissingProductOrSku() throws Exception {
        for (String body : new String[]{
                "{\"sku_id\":\"sku-001\",\"quantity\":2}",
                "{\"product_id\":\"\",\"sku_id\":\"sku-001\",\"quantity\":2}",
                "{\"product_id\":\"product-001\",\"sku_id\":\" \",\"quantity\":2}"}) {
            mvc.perform(post(PATH).with(gateway("buyer-001"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));
        }

        verifyNoInteractions(addItemService);
    }

    @Test
    void shouldRejectSpoofedBuyerBodyField() throws Exception {
        String body = """
                {"product_id":"product-001","sku_id":"sku-001",
                 "quantity":2,"buyer_user_id":"admin"}
                """;

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));

        verifyNoInteractions(addItemService);
    }

    @Test
    void shouldRejectIdempotencyKeyUntilPhase5() throws Exception {
        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .header("Idempotency-Key", "add-item-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_INVALID_INPUT"));

        verifyNoInteractions(addItemService);
    }

    @Test
    void shouldReturn409ForUnavailableProduct() throws Exception {
        when(addItemService.addItem("buyer-001", "product-001", "sku-001", 2))
                .thenThrow(new CartException(
                        CartErrorCode.CART_PRODUCT_UNAVAILABLE,
                        "Product or SKU is unavailable"));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_PRODUCT_UNAVAILABLE"));
    }

    @Test
    void shouldReturn409WhenCartIsFull() throws Exception {
        when(addItemService.addItem("buyer-001", "product-001", "sku-001", 2))
                .thenThrow(new CartException(
                        CartErrorCode.CART_TOO_MANY_ITEMS,
                        "Cart cannot contain more than 100 items"));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_TOO_MANY_ITEMS"));
    }

    @Test
    void shouldReturn503ForProductCatalogFailure() throws Exception {
        when(addItemService.addItem("buyer-001", "product-001", "sku-001", 2))
                .thenThrow(new CartException(
                        CartErrorCode.CART_DEPENDENCY_UNAVAILABLE,
                        "Product Catalog is unavailable"));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CART_DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void shouldReturn503ForMongoFailure() throws Exception {
        when(addItemService.addItem("buyer-001", "product-001", "sku-001", 2))
                .thenThrow(new CartException(
                        CartErrorCode.CART_STORAGE_UNAVAILABLE,
                        "Cart storage is unavailable"));

        mvc.perform(post(PATH).with(gateway("buyer-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
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
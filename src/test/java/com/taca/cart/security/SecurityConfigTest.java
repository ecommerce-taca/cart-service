package com.taca.cart.security;

import com.taca.cart.api.HealthController;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {
    private static final String TOKEN = "a".repeat(64);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MongoOperations mongoOperations;

    @DynamicPropertySource
    static void gatewayToken(DynamicPropertyRegistry registry) {
        registry.add("CART_GATEWAY_TOKEN", () -> TOKEN);
    }

    @Test
    void shouldAllowHealthEndpointsWithoutAuthentication() throws Exception {
        when(mongoOperations.executeCommand(any(Document.class)))
                .thenReturn(new Document("isWritablePrimary", true));

        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldRejectUnauthenticatedBuyerRequests() throws Exception {
        mvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/cart/items"))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/v1/cart/items/item-001"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v1/cart/items/item-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectSpoofedHeaders() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-User-ID", "buyer-001")
                        .header("X-User-Roles", "BUYER"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/internal/carts/buyer-001")
                        .header("X-Internal-Service-Token", "fake-token"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/cart")
                        .header("Authorization", "Bearer fake-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowBuyerAuthorityForCartOperations() throws Exception {
        mvc.perform(get("/api/v1/cart").with(gatewayBuyer()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/cart/items").with(gatewayBuyer()))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/cart/items/item-001").with(gatewayBuyer()))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/cart/items/item-001").with(gatewayBuyer()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldBlockBuyerFromInternalApis() throws Exception {
        mvc.perform(get("/api/v1/internal/carts/buyer-001").with(buyer()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/internal/carts/buyer-001/checked-out").with(buyer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowInternalAuthorityOnlyForInternalApis() throws Exception {
        mvc.perform(get("/api/v1/internal/carts/buyer-001").with(internal()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/internal/carts/buyer-001/checked-out").with(internal()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/cart").with(internal()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldDenyUnknownRoutesAndUnsupportedMethods() throws Exception {
        mvc.perform(get("/api/v1/unknown").with(buyer()))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/cart/items").with(buyer()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/health/live").with(buyer()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/cart/items").with(internal()))
                .andExpect(status().isUnauthorized());
    }

    private RequestPostProcessor gatewayBuyer() {
        return request -> {
            request.addHeader(GatewayBuyerAuthenticationFilter.GATEWAY_TOKEN_HEADER, TOKEN);
            request.addHeader(GatewayBuyerAuthenticationFilter.USER_ID_HEADER, "buyer-001");
            return request;
        };
    }

    private RequestPostProcessor buyer() {
        return user("buyer-001")
                .authorities(new SimpleGrantedAuthority(CartAuthorities.BUYER));
    }

    private RequestPostProcessor internal() {
        return user("checkout-service")
                .authorities(new SimpleGrantedAuthority(CartAuthorities.INTERNAL));
    }

    @RestController
    public static class ProbeController {
        @GetMapping("/api/v1/cart")
        public String getCart() {
            return "cart";
        }

        @PostMapping("/api/v1/cart/items")
        public String addItem() {
            return "created";
        }

        @PutMapping("/api/v1/cart/items/{itemId}")
        public String updateItem(@PathVariable String itemId) {
            return "updated";
        }

        @DeleteMapping("/api/v1/cart/items/{itemId}")
        public String deleteItem(@PathVariable String itemId) {
            return "deleted";
        }

        @GetMapping("/api/v1/internal/carts/{buyerUserId}")
        public String getInternalCart(@PathVariable String buyerUserId) {
            return "internal-cart";
        }

        @PostMapping("/api/v1/internal/carts/{buyerUserId}/checked-out")
        public String checkedOut(@PathVariable String buyerUserId) {
            return "checked-out";
        }
    }
}

package com.taca.cart.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GatewayBuyerAuthenticationTest.ProbeController.class)
@Import({
        SecurityConfig.class,
        ActorContextResolver.class,
        GatewayBuyerAuthenticationTest.ProbeController.class
})
class GatewayBuyerAuthenticationTest {
    private static final String TOKEN = "a".repeat(64);

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void gatewayToken(DynamicPropertyRegistry registry) {
        registry.add("CART_GATEWAY_TOKEN", () -> TOKEN);
    }

    @Test
    void shouldAuthenticateTrustedGatewayBuyer() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-Gateway-Service-Token", TOKEN)
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_user_id").value("buyer-001"));
    }

    @Test
    void shouldRejectMissingGatewayToken() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidGatewayToken() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-Gateway-Service-Token", "b".repeat(64))
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectMissingBuyerIdentity() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-Gateway-Service-Token", TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidBuyerIdentity() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-Gateway-Service-Token", TOKEN)
                        .header("X-User-ID", "../buyer-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotTrustUserRolesHeader() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-Gateway-Service-Token", TOKEN)
                        .header("X-User-ID", "buyer-001")
                        .header("X-User-Roles", "ADMIN,CART_INTERNAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_user_id").value("buyer-001"));
    }

    @Test
    void shouldRejectDuplicateIdentityHeaders() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-Gateway-Service-Token", TOKEN)
                        .header("X-User-ID", "buyer-001", "buyer-002"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/cart")
                        .header("X-Gateway-Service-Token", TOKEN, "b".repeat(64))
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotGrantInternalAccessToGatewayBuyer() throws Exception {
        mvc.perform(get("/api/v1/internal/carts/buyer-001")
                        .header("X-Gateway-Service-Token", TOKEN)
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    public static class ProbeController {
        private final ActorContextResolver resolver;

        public ProbeController(ActorContextResolver resolver) {
            this.resolver = resolver;
        }

        @GetMapping(value = "/api/v1/cart", produces = MediaType.APPLICATION_JSON_VALUE)
        public Map<String, String> getCart() {
            ActorContext actor = resolver.requireBuyer();
            return Map.of("buyer_user_id", actor.buyerUserId());
        }
    }
}

package com.taca.cart.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InternalServiceAuthenticationTest.ProbeController.class)
@Import({SecurityConfig.class, InternalServiceAuthenticationTest.ProbeController.class})
class InternalServiceAuthenticationTest {
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_PATH = "/api/v1/internal/carts/buyer-001";

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void configureTokens(DynamicPropertyRegistry registry) {
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
    }

    @Test
    void shouldAuthenticateInternalGet() throws Exception {
        mvc.perform(get(INTERNAL_PATH)
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("checkout-service"))
                .andExpect(jsonPath("$.authority").value(CartAuthorities.INTERNAL));
    }

    @Test
    void shouldAuthenticateCheckedOutPost() throws Exception {
        mvc.perform(post(INTERNAL_PATH + "/checked-out")
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("checkout-service"));
    }

    @Test
    void shouldRejectMissingInternalToken() throws Exception {
        mvc.perform(get(INTERNAL_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidInternalToken() throws Exception {
        mvc.perform(get(INTERNAL_PATH)
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER, "c".repeat(64)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectMalformedInternalToken() throws Exception {
        mvc.perform(get(INTERNAL_PATH)
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER, "invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectDuplicateInternalTokenHeaders() throws Exception {
        mvc.perform(get(INTERNAL_PATH)
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER,
                                INTERNAL_TOKEN, "c".repeat(64)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(INTERNAL_PATH)
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER,
                                INTERNAL_TOKEN + "," + INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectGatewayTokenForInternalApi() throws Exception {
        mvc.perform(get(INTERNAL_PATH)
                        .header(GatewayBuyerAuthenticationFilter.GATEWAY_TOKEN_HEADER, GATEWAY_TOKEN)
                        .header(GatewayBuyerAuthenticationFilter.USER_ID_HEADER, "buyer-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInternalTokenForBuyerApi() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotTrustSpoofedBuyerHeadersForInternal() throws Exception {
        mvc.perform(get(INTERNAL_PATH)
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER, INTERNAL_TOKEN)
                        .header("X-User-ID", "admin-user")
                        .header("X-User-Roles", "ADMIN,CART_BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("checkout-service"))
                .andExpect(jsonPath("$.authority").value(CartAuthorities.INTERNAL));
    }

    @Test
    void shouldDenyUnknownInternalRoutes() throws Exception {
        mvc.perform(get(INTERNAL_PATH + "/unknown")
                        .header(InternalServiceAuthenticationFilter.TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isForbidden());
    }

    @RestController
    @RequestMapping("/api/v1/internal/carts")
    public static class ProbeController {
        @GetMapping("/{buyerUserId}")
        public Map<String, String> getCart(@PathVariable String buyerUserId) {
            return currentCaller();
        }

        @PostMapping("/{buyerUserId}/checked-out")
        public Map<String, String> checkedOut(@PathVariable String buyerUserId) {
            return currentCaller();
        }

        private Map<String, String> currentCaller() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return Map.of(
                    "caller", authentication.getName(),
                    "authority", authentication.getAuthorities().iterator().next().getAuthority()
            );
        }
    }
}

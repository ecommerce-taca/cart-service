
package com.taca.cart.security;

import com.taca.cart.api.HealthController;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HealthController.class)
@Import({
        SecurityConfig.class,
        ActorContextResolver.class,
        SecurityBoundaryIntegrationTest.ProbeController.class
})
class SecurityBoundaryIntegrationTest {
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String CART = "/api/v1/cart";
    private static final String INTERNAL = "/api/v1/internal/carts/buyer-001";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MongoOperations mongoOperations;

    @DynamicPropertySource
    static void configureTokens(DynamicPropertyRegistry registry) {
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
    }

    @Test
    void shouldAuthenticateBuyerForAllAllowedCartMethods() throws Exception {
        mvc.perform(get(CART).with(gateway("buyer-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_user_id").value("buyer-001"));

        mvc.perform(post(CART + "/items").with(gateway("buyer-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authority").value(CartAuthorities.BUYER));

        mvc.perform(put(CART + "/items/item-001").with(gateway("buyer-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_user_id").value("buyer-001"));

        mvc.perform(delete(CART + "/items/item-001").with(gateway("buyer-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authority").value(CartAuthorities.BUYER));
    }

    @Test
    void shouldPreserveVerifiedBuyerIdentity() throws Exception {
        mvc.perform(get(CART).with(gateway("buyer-A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_user_id").value("buyer-A"));

        mvc.perform(get(CART).with(gateway("buyer-B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_user_id").value("buyer-B"));
    }

    @Test
    void shouldRejectSpoofedBuyerWithoutGatewayToken() throws Exception {
        mvc.perform(get(CART)
                        .header("X-User-ID", "admin")
                        .header("X-User-Roles", "ADMIN,CART_BUYER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectInvalidGatewayCredentials() throws Exception {
        mvc.perform(get(CART)
                        .header("X-User-ID", "buyer-001")
                        .header("X-Gateway-Service-Token", "c".repeat(64)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(CART)
                        .header("X-User-ID", "buyer-001")
                        .header("Authorization", "Bearer fake-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectMalformedAndDuplicateBuyerHeaders() throws Exception {
        mvc.perform(get(CART)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", "../admin"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(CART)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", "buyer-001", "buyer-002"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(CART)
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN, GATEWAY_TOKEN)
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotGrantInternalAuthorityFromBuyerRolesHeader() throws Exception {
        mvc.perform(get(CART)
                        .with(gateway("buyer-001"))
                        .header("X-User-Roles", "ADMIN,CART_INTERNAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_user_id").value("buyer-001"))
                .andExpect(jsonPath("$.authority").value(CartAuthorities.BUYER));
    }

    @Test
    void shouldAuthenticateInternalReadAndCleanup() throws Exception {
        mvc.perform(get(INTERNAL).with(internal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("checkout-service"))
                .andExpect(jsonPath("$.authority").value(CartAuthorities.INTERNAL));

        mvc.perform(post(INTERNAL + "/checked-out").with(internal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("checkout-service"));
    }

    @Test
    void shouldRejectInvalidInternalCredentials() throws Exception {
        mvc.perform(get(INTERNAL))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(INTERNAL)
                        .header("X-Internal-Service-Token", "c".repeat(64)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(INTERNAL)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectCrossCredentialUse() throws Exception {
        mvc.perform(get(INTERNAL).with(gateway("buyer-001")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));

        mvc.perform(get(CART).with(internal()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));
    }

    @Test
    void shouldNotPromoteInternalCallerFromBuyerHeaders() throws Exception {
        mvc.perform(get(INTERNAL)
                        .with(internal())
                        .header("X-User-ID", "admin")
                        .header("X-User-Roles", "CART_BUYER,ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("checkout-service"))
                .andExpect(jsonPath("$.authority").value(CartAuthorities.INTERNAL));
    }

    @Test
    void shouldDenyUnknownInternalRoutes() throws Exception {
        mvc.perform(get(INTERNAL + "/unknown")
                        .with(internal())
                        .header("X-Request-ID", "req-denied-001"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Request-ID", "req-denied-001"))
                .andExpect(jsonPath("$.error.code").value("CART_FORBIDDEN"));
    }

    @Test
    void shouldRejectUnsupportedBuyerRoutes() throws Exception {
        mvc.perform(get(CART + "/items").with(gateway("buyer-001")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(CART).with(gateway("buyer-001")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotReuseAuthenticationBetweenRequests() throws Exception {
        mvc.perform(get(CART).with(gateway("buyer-001")))
                .andExpect(status().isOk());

        mvc.perform(get(CART))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(CART).with(gateway("buyer-002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_user_id").value("buyer-002"));

        mvc.perform(get(INTERNAL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldHandleRequestIdsForSecurityFailures() throws Exception {
        mvc.perform(get(CART)
                        .header("X-Request-ID", "req-security-001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", "req-security-001"));

        var result = mvc.perform(get(CART)
                        .header("X-Request-ID", "invalid request id"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String requestId = result.getResponse().getHeader("X-Request-ID");
        assertNotNull(requestId);
        assertNotEquals("invalid request id", requestId);
        assertTrue(requestId.matches("[A-Za-z0-9._:-]{1,64}"));
    }

    @Test
    void shouldNotExposeSecretsInSecurityErrors() throws Exception {
        String invalidToken = "c".repeat(64);

        var result = mvc.perform(get(INTERNAL)
                        .header("X-Internal-Service-Token", invalidToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(invalidToken));
        assertFalse(body.contains(INTERNAL_TOKEN));
        assertFalse(body.contains(GATEWAY_TOKEN));
    }

    @Test
    void shouldAllowHealthWithoutAuthentication() throws Exception {
        when(mongoOperations.executeCommand(any(Document.class)))
                .thenReturn(new Document("isWritablePrimary", true));

        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        verify(mongoOperations, times(1)).executeCommand(any(Document.class));
    }

    private RequestPostProcessor gateway(String buyerUserId) {
        return request -> {
            request.addHeader(GatewayBuyerAuthenticationFilter.GATEWAY_TOKEN_HEADER, GATEWAY_TOKEN);
            request.addHeader(GatewayBuyerAuthenticationFilter.USER_ID_HEADER, buyerUserId);
            return request;
        };
    }

    private RequestPostProcessor internal() {
        return request -> {
            request.addHeader(InternalServiceAuthenticationFilter.TOKEN_HEADER, INTERNAL_TOKEN);
            return request;
        };
    }

    @RestController
    public static class ProbeController {
        private final ActorContextResolver actorContextResolver;

        public ProbeController(ActorContextResolver actorContextResolver) {
            this.actorContextResolver = actorContextResolver;
        }

        @GetMapping("/api/v1/cart")
        public Map<String, String> getCart() {
            return buyerResponse();
        }

        @PostMapping("/api/v1/cart/items")
        public Map<String, String> addItem() {
            return buyerResponse();
        }

        @PutMapping("/api/v1/cart/items/{itemId}")
        public Map<String, String> updateItem(@PathVariable String itemId) {
            return buyerResponse();
        }

        @DeleteMapping("/api/v1/cart/items/{itemId}")
        public Map<String, String> deleteItem(@PathVariable String itemId) {
            return buyerResponse();
        }

        @GetMapping("/api/v1/internal/carts/{buyerUserId}")
        public Map<String, String> getInternalCart(@PathVariable String buyerUserId) {
            return internalResponse();
        }

        @PostMapping("/api/v1/internal/carts/{buyerUserId}/checked-out")
        public Map<String, String> checkedOut(@PathVariable String buyerUserId) {
            return internalResponse();
        }

        private Map<String, String> buyerResponse() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return Map.of(
                    "buyer_user_id", actorContextResolver.requireBuyer().buyerUserId(),
                    "authority", authentication.getAuthorities().iterator().next().getAuthority()
            );
        }

        private Map<String, String> internalResponse() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return Map.of(
                    "caller", authentication.getName(),
                    "authority", authentication.getAuthorities().iterator().next().getAuthority()
            );
        }
    }
}

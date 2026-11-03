
package com.taca.cart.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SecurityErrorResponseTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityErrorResponseTest.ProbeController.class})
class SecurityErrorResponseTest {
    private static final String GATEWAY_TOKEN = "a".repeat(64);
    private static final String INTERNAL_TOKEN = "b".repeat(64);
    private static final String INTERNAL_PATH = "/api/v1/internal/carts/buyer-001";

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void configureTokens(DynamicPropertyRegistry registry) {
        registry.add("CART_GATEWAY_TOKEN", () -> GATEWAY_TOKEN);
        registry.add("CART_INTERNAL_TOKEN", () -> INTERNAL_TOKEN);
    }

    @Test
    void shouldReturnJson401WhenGatewayTokenIsMissing() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-User-ID", "buyer-001")
                        .header("X-Request-ID", "req-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("X-Request-ID", "req-401"))
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.message").value("Authentication required"))
                .andExpect(jsonPath("$.error.details").isMap());
    }

    @Test
    void shouldRejectInvalidGatewayTokenWithoutLeakingSecret() throws Exception {
        String invalidToken = "c".repeat(64);

        var result = mvc.perform(get("/api/v1/cart")
                        .header("X-User-ID", "buyer-001")
                        .header("X-Gateway-Service-Token", invalidToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains(invalidToken));
        assertFalse(result.getResponse().getContentAsString().contains(GATEWAY_TOKEN));
    }

    @Test
    void shouldReturnJson401WhenInternalTokenIsMissing() throws Exception {
        mvc.perform(get(INTERNAL_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));
    }

    @Test
    void shouldReturnJson401WhenInternalTokenIsInvalid() throws Exception {
        mvc.perform(get(INTERNAL_PATH)
                        .header("X-Internal-Service-Token", "c".repeat(64)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CART_UNAUTHENTICATED"));
    }

    @Test
    void shouldReturnJson403ForDeniedInternalRoute() throws Exception {
        mvc.perform(get(INTERNAL_PATH + "/unknown")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .header("X-Request-ID", "req-403"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("X-Request-ID", "req-403"))
                .andExpect(jsonPath("$.error.code").value("CART_FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("Access denied"))
                .andExpect(jsonPath("$.error.details").isMap());
    }

    @Test
    void shouldReplaceInvalidRequestId() throws Exception {
        var result = mvc.perform(get("/api/v1/cart")
                        .header("X-Request-ID", "invalid request id"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String requestId = result.getResponse().getHeader("X-Request-ID");

        assertNotNull(requestId);
        assertNotEquals("invalid request id", requestId);
        assertTrue(requestId.matches("[A-Za-z0-9._:-]{1,64}"));
    }

    @Test
    void shouldGenerateDifferentRequestIdsForSeparateFailures() throws Exception {
        var first = mvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        var second = mvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String firstId = first.getResponse().getHeader("X-Request-ID");
        String secondId = second.getResponse().getHeader("X-Request-ID");

        assertNotNull(firstId);
        assertNotNull(secondId);
        assertNotEquals(firstId, secondId);
    }

    @Test
    void shouldStillAllowTrustedGatewayRequest() throws Exception {
        mvc.perform(get("/api/v1/cart")
                        .header("X-Gateway-Service-Token", GATEWAY_TOKEN)
                        .header("X-User-ID", "buyer-001"))
                .andExpect(status().isOk());
    }

    @RestController
    public static class ProbeController {
        @GetMapping("/api/v1/cart")
        public String getCart() {
            return "ok";
        }
    }
}

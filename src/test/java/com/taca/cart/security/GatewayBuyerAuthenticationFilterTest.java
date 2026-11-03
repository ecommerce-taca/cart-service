package com.taca.cart.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class GatewayBuyerAuthenticationFilterTest {
    @Test
    void shouldFailClosedWhenGatewaySecretIsMissing() throws Exception {
        var filter = new GatewayBuyerAuthenticationFilter("");
        var request = new MockHttpServletRequest("GET", "/api/v1/cart");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        request.addHeader(GatewayBuyerAuthenticationFilter.GATEWAY_TOKEN_HEADER, "a".repeat(64));
        request.addHeader(GatewayBuyerAuthenticationFilter.USER_ID_HEADER, "buyer-001");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void shouldFailClosedWhenConfiguredSecretIsInvalid() throws Exception {
        var filter = new GatewayBuyerAuthenticationFilter("invalid-secret");
        var request = new MockHttpServletRequest("GET", "/api/v1/cart");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        request.addHeader(GatewayBuyerAuthenticationFilter.GATEWAY_TOKEN_HEADER, "a".repeat(64));
        request.addHeader(GatewayBuyerAuthenticationFilter.USER_ID_HEADER, "buyer-001");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }
}

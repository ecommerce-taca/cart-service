package com.taca.cart.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class InternalServiceAuthenticationFilterTest {
    private static final String TOKEN = "b".repeat(64);
    private static final String PATH = "/api/v1/internal/carts/buyer-001";

    @Test
    void shouldFailClosedWhenInternalSecretIsMissing() throws Exception {
        var filter = new InternalServiceAuthenticationFilter("");
        var request = internalRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void shouldFailClosedWhenInternalSecretIsInvalid() throws Exception {
        var filter = new InternalServiceAuthenticationFilter("invalid-secret");
        var request = internalRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void shouldNotInterceptBuyerRoutes() throws Exception {
        var filter = new InternalServiceAuthenticationFilter(TOKEN);
        var request = new MockHttpServletRequest("GET", "/api/v1/cart");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void shouldNotInterceptHealthEndpoints() throws Exception {
        var filter = new InternalServiceAuthenticationFilter(TOKEN);
        var request = new MockHttpServletRequest("GET", "/health/live");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    private MockHttpServletRequest internalRequest() {
        var request = new MockHttpServletRequest("GET", PATH);
        request.addHeader(InternalServiceAuthenticationFilter.TOKEN_HEADER, TOKEN);
        return request;
    }
}


package com.taca.cart.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {
    public static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String INTERNAL_PREFIX = "/api/v1/internal/";
    private static final String SERVICE_NAME = "checkout-service";

    private final byte[] expectedTokenHash;

    public InternalServiceAuthenticationFilter(String configuredToken) {
        expectedTokenHash = isValidToken(configuredToken) ? sha256(configuredToken) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/v1/internal") && !path.startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = singleHeader(request, TOKEN_HEADER);

        if (!isTrustedToken(token)) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                SERVICE_NAME, null,
                List.of(new SimpleGrantedAuthority(CartAuthorities.INTERNAL))
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isTrustedToken(String token) {
        return expectedTokenHash != null && isValidToken(token)
                && MessageDigest.isEqual(expectedTokenHash, sha256(token));
    }

    private static boolean isValidToken(String token) {
        return token != null && token.matches("[0-9a-fA-F]{64}");
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) return null;

        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}

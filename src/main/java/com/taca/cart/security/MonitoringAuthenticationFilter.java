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

public class MonitoringAuthenticationFilter extends OncePerRequestFilter {
    public static final String AUTHORITY = "CART_MONITORING";
    private static final String PATH = "/actuator/prometheus";
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedTokenHash;

    public MonitoringAuthenticationFilter(String configuredToken) {
        expectedTokenHash = validToken(configuredToken)
                ? sha256(configuredToken) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());
        return !PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String token = readBearerToken(request);

        if (!trusted(token)) {
            SecurityContextHolder.clearContext();
            SecurityErrorWriter.unauthorized(request, response);
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "prometheus-scraper", null,
                List.of(new SimpleGrantedAuthority(AUTHORITY)));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String readBearerToken(HttpServletRequest request) {
        var headers = request.getHeaders("Authorization");
        if (!headers.hasMoreElements()) return null;

        String value = headers.nextElement();
        if (headers.hasMoreElements()
                || !value.startsWith(BEARER_PREFIX)) return null;

        return value.substring(BEARER_PREFIX.length());
    }

    private boolean trusted(String token) {
        return expectedTokenHash != null
                && validToken(token)
                && MessageDigest.isEqual(expectedTokenHash, sha256(token));
    }

    private static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-fA-F]{64}");
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

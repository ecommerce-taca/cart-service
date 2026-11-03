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

public class GatewayBuyerAuthenticationFilter extends OncePerRequestFilter {
    public static final String GATEWAY_TOKEN_HEADER = "X-Gateway-Service-Token";
    public static final String USER_ID_HEADER = "X-User-ID";

    private final byte[] expectedTokenHash;

    public GatewayBuyerAuthenticationFilter(String configuredToken) {
        expectedTokenHash = isValidToken(configuredToken) ? sha256(configuredToken) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();

        return switch (method) {
            case "GET" -> !path.equals("/api/v1/cart");
            case "POST" -> !path.equals("/api/v1/cart/items");
            case "PUT", "DELETE" -> !isItemPath(path);
            default -> true;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = singleHeader(request, GATEWAY_TOKEN_HEADER);
        String buyerUserId = singleHeader(request, USER_ID_HEADER);

        if (!isTrustedToken(token) || !isValidBuyerId(buyerUserId)) {
            SecurityContextHolder.clearContext();
            SecurityErrorWriter.unauthorized(request, response);
            return;
        }

        ActorContext actor = new ActorContext(buyerUserId);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                actor, null,
                List.of(new SimpleGrantedAuthority(CartAuthorities.BUYER))
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

    private static boolean isValidBuyerId(String buyerUserId) {
        return buyerUserId != null
                && buyerUserId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static boolean isItemPath(String path) {
        String prefix = "/api/v1/cart/items/";
        return path.startsWith(prefix)
                && path.length() > prefix.length()
                && path.indexOf('/', prefix.length()) == -1;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) return null;

        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}

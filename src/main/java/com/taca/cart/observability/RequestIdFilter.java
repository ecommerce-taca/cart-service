package com.taca.cart.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-Request-ID";
    private static final String ATTRIBUTE = "cart.requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);

        if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,64}")) {
            requestId = UUID.randomUUID().toString();
        }

        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        chain.doFilter(request, response);
    }

    public static String resolve(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : UUID.randomUUID().toString();
    }
}
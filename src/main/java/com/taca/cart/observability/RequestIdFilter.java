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
        ensure(request, response);
        chain.doFilter(request, response);
    }

    public static String ensure(HttpServletRequest request, HttpServletResponse response) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String id && valid(id)) {
            response.setHeader(HEADER, id);
            return id;
        }

        String requestId = readHeader(request);
        if (requestId == null) requestId = UUID.randomUUID().toString();

        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        return requestId;
    }

    public static String resolve(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id && valid(id)
                ? id : UUID.randomUUID().toString();
    }

    private static String readHeader(HttpServletRequest request) {
        var values = request.getHeaders(HEADER);
        if (!values.hasMoreElements()) return null;

        String value = values.nextElement();
        return !values.hasMoreElements() && valid(value) ? value : null;
    }

    private static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,64}");
    }
}
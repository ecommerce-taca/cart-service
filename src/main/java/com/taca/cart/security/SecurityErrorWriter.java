package com.taca.cart.security;

import com.taca.cart.api.dto.ApiErrorResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.observability.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class SecurityErrorWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SecurityErrorWriter() {}

    public static void unauthorized(HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                CartErrorCode.CART_UNAUTHENTICATED, "Authentication required");
    }

    public static void forbidden(HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        write(request, response, HttpServletResponse.SC_FORBIDDEN,
                CartErrorCode.CART_FORBIDDEN, "Access denied");
    }

    private static void write(HttpServletRequest request, HttpServletResponse response,
                              int status, CartErrorCode code, String message) throws IOException {
        if (response.isCommitted()) return;

        RequestIdFilter.ensure(request, response);

        var error = new ApiErrorResponse.ApiError(
                code.name(), message, Map.of(), MDC.get("traceId"));

        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");

        response.getWriter().write(JSON.writeValueAsString(new ApiErrorResponse(error)));
    }
}
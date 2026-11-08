package com.taca.cart.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpRequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log =
            LoggerFactory.getLogger(HttpRequestLoggingFilter.class);

    private static final String EVENT = "http.request.completed";
    private static final String UNMATCHED_ROUTE = "UNMATCHED";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = RequestIdFilter.ensure(request, response);
        String previousRequestId = MDC.get("request_id");
        long startedAt = System.nanoTime();
        boolean failed = false;

        MDC.put("request_id", requestId);

        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            failed = true;
            throw ex;
        } finally {
            try {
                int status = response.getStatus();
                if (failed && status < 500) status = 500;

                long durationMs = TimeUnit.NANOSECONDS.toMillis(
                        Math.max(0L, System.nanoTime() - startedAt));

                logCompletion(request, status, durationMs);
            } finally {
                if (previousRequestId == null) {
                    MDC.remove("request_id");
                } else {
                    MDC.put("request_id", previousRequestId);
                }
            }
        }
    }

    private void logCompletion(
            HttpServletRequest request, int status, long durationMs) {
        Object matchedRoute = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        String route = matchedRoute instanceof String pattern
                ? pattern : UNMATCHED_ROUTE;

        var builder = status >= 500 ? log.atWarn() : log.atInfo();

        builder.addKeyValue("event", EVENT)
                .addKeyValue("method", request.getMethod())
                .addKeyValue("route", route)
                .addKeyValue("status_code", status)
                .addKeyValue("duration_ms", durationMs)
                .log("HTTP request completed");
    }
}

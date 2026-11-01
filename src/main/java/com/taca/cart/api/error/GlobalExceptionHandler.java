package com.taca.cart.api.error;

import com.taca.cart.api.dto.ApiErrorResponse;
import com.taca.cart.observability.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CartException.class)
    public ResponseEntity<ApiErrorResponse> handleCartException(
            CartException ex, HttpServletRequest request) {
        return respond(ex.getCode(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return respond(CartErrorCode.CART_INVALID_INPUT, "Invalid request body", details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        if (ex.getStatusCode().is5xxServerError()) {
            return respond(CartErrorCode.CART_INTERNAL_ERROR, "Internal server error", Map.of());
        }
        return respond(CartErrorCode.CART_INVALID_INPUT, "Invalid request parameter", Map.of());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            Exception ex, HttpServletRequest request) {
        return respond(CartErrorCode.CART_INVALID_INPUT, "Invalid request", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled error request_id={} type={}",
                RequestIdFilter.resolve(request), ex.getClass().getSimpleName());
        return respond(CartErrorCode.CART_INTERNAL_ERROR, "Internal server error", Map.of());
    }

    private ResponseEntity<ApiErrorResponse> respond(
            CartErrorCode code, String message, Map<String, ?> details) {
        var error = new ApiErrorResponse.ApiError(
                code.name(), message, details, MDC.get("traceId"));

        return ResponseEntity.status(code.status()).body(new ApiErrorResponse(error));
    }
}
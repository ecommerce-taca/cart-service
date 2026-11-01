package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record ApiErrorResponse(ApiError error) {
    public record ApiError(
            String code,
            String message,
            Map<String, ?> details,
            @JsonProperty("trace_id") String traceId
    ) {}
}
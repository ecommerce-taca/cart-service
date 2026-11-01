package com.taca.cart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiResponse<T>(T data, Meta meta) {
    public static <T> ApiResponse<T> of(T data, String requestId) {
        return new ApiResponse<>(data, new Meta(requestId));
    }

    public record Meta(@JsonProperty("request_id") String requestId) {}
}
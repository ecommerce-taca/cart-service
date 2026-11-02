
package com.taca.cart.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CartIdempotencyKey(
        CartIdempotencyId id, String requestHash,
        Map<String, Object> response, int responseStatus,
        Instant expiresAt, Instant createdAt
) {
    public CartIdempotencyKey {
        Objects.requireNonNull(id, "id");

        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Request hash must be a lowercase SHA-256 hex string");
        }

        response = immutableJsonObject(Objects.requireNonNull(response, "response"));

        if (!response.containsKey("data") || !(response.get("meta") instanceof Map<?, ?> meta)
                || !(meta.get("request_id") instanceof String requestId) || requestId.isBlank()) {
            throw new IllegalArgumentException("Committed response must include data and meta.request_id");
        }

        if (responseStatus != 201) throw new IllegalArgumentException("Response status must be 201");

        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");

        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Expiry must follow creation");
        }
    }

    private static Map<String, Object> immutableJsonObject(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableJsonValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("JSON object key must be a string");
                }
                copy.put(name, immutableJsonValue(nested));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CartIdempotencyKey::immutableJsonValue).toList();
        }
        return value;
    }
}

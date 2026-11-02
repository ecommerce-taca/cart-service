
package com.taca.cart.persistence.document;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.time.Instant;
import java.util.Map;

@Document(collection = "cart_idempotency_keys")
public record CartIdempotencyKeyDocument(
        @MongoId CartIdempotencyIdDocument id,
        @Field("request_hash") String requestHash,
        @Field("response") Map<String, Object> response,
        @Field("response_status") int responseStatus,
        @Field("expires_at") Instant expiresAt,
        @Field("created_at") Instant createdAt
) {}


package com.taca.cart.persistence.document;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.time.Instant;
import java.util.List;

@Document(collection = "carts")
public record CartDocument(
        @MongoId(FieldType.STRING) String id,
        @Field("buyer_user_id") String buyerUserId,
        @Field("items") List<CartItemDocument> items,
        @Field("version") long version,
        @Field("created_at") Instant createdAt,
        @Field("updated_at") Instant updatedAt
) {}

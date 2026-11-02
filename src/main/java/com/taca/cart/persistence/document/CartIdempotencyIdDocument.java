
package com.taca.cart.persistence.document;

import org.springframework.data.mongodb.core.mapping.Field;

public record CartIdempotencyIdDocument(
        @Field(name = "buyer_user_id", order = 1) String buyerUserId,
        @Field(name = "key", order = 2) String key
) {}

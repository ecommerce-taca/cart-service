
package com.taca.cart.persistence.repository;

import com.taca.cart.persistence.document.CartIdempotencyIdDocument;
import com.taca.cart.persistence.document.CartIdempotencyKeyDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CartIdempotencyMongoRepository
        extends MongoRepository<CartIdempotencyKeyDocument, CartIdempotencyIdDocument> {
}

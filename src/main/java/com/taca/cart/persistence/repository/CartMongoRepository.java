
package com.taca.cart.persistence.repository;

import com.taca.cart.persistence.document.CartDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CartMongoRepository extends MongoRepository<CartDocument, String> {
    Optional<CartDocument> findByBuyerUserId(String buyerUserId);
}

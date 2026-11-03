
package com.taca.cart.persistence;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.config.CartProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.persistence.document.CartDocument;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

@Component
public class CartCasPersistence {
    private final MongoOperations mongoOperations;
    private final CartPersistenceMapper mapper;
    private final CartProperties properties;

    public CartCasPersistence(MongoOperations mongoOperations,
                              CartPersistenceMapper mapper,
                              CartProperties properties) {
        this.mongoOperations = mongoOperations;
        this.mapper = mapper;
        this.properties = properties;
    }

    public Optional<Cart> findByBuyerUserId(String buyerUserId) {
        validateBuyer(buyerUserId);
        Query query = Query.query(Criteria.where("buyer_user_id").is(buyerUserId));
        CartDocument document = mongoOperations.findOne(query, CartDocument.class);
        return Optional.ofNullable(document).map(mapper::toDomain);
    }

    public Cart mutate(String buyerUserId, Function<Optional<Cart>, Cart> mutation) {
        validateBuyer(buyerUserId);
        Objects.requireNonNull(mutation, "mutation");

        for (int attempt = 0; attempt <= properties.writeRetryMax(); attempt++) {
            Optional<Cart> snapshot = findByBuyerUserId(buyerUserId);
            Cart candidate = Objects.requireNonNull(mutation.apply(snapshot), "mutation result");

            if (snapshot.isEmpty()) {
                validateNewCart(candidate, buyerUserId);
                Cart created = new Cart(
                        candidate.id(), buyerUserId, candidate.items(),
                        1L, candidate.createdAt(), candidate.updatedAt()
                );

                try {
                    mongoOperations.insert(mapper.toDocument(created));
                    return created;
                } catch (DuplicateKeyException ex) {
                    if (attempt == properties.writeRetryMax()) throw versionConflict();
                    continue;
                }
            }

            Cart current = snapshot.orElseThrow();
            validateExistingCart(candidate, current);

            if (current.items().equals(candidate.items())) return current;

            Instant updatedAt = nextUpdatedAt(current.updatedAt());
            Query query = Query.query(Criteria.where("_id").is(current.id())
                    .and("buyer_user_id").is(current.buyerUserId())
                    .and("version").is(current.version()));

            Update update = new Update()
                    .set("items", mapper.toDocument(candidate).items())
                    .set("updated_at", updatedAt)
                    .inc("version", 1L);

            var result = mongoOperations.updateFirst(query, update, CartDocument.class);

            if (result.getMatchedCount() == 1) {
                return new Cart(
                        current.id(), current.buyerUserId(), candidate.items(),
                        Math.addExact(current.version(), 1L),
                        current.createdAt(), updatedAt
                );
            }

            if (attempt == properties.writeRetryMax()) throw versionConflict();
        }

        throw versionConflict();
    }

    private void validateBuyer(String buyerUserId) {
        if (buyerUserId == null || buyerUserId.isBlank()) {
            throw new IllegalArgumentException("Buyer ID must not be blank");
        }
    }

    private void validateNewCart(Cart candidate, String buyerUserId) {
        if (!buyerUserId.equals(candidate.buyerUserId()) || candidate.version() != 0L) {
            throw new IllegalArgumentException("Invalid initial cart state");
        }
    }

    private void validateExistingCart(Cart candidate, Cart current) {
        if (!current.id().equals(candidate.id())
                || !current.buyerUserId().equals(candidate.buyerUserId())
                || current.version() != candidate.version()
                || !current.createdAt().equals(candidate.createdAt())) {
            throw new IllegalArgumentException("Mutation must preserve cart identity and version");
        }
    }

    private Instant nextUpdatedAt(Instant previous) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return now.isAfter(previous) ? now : previous.truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
    }

    private CartException versionConflict() {
        return new CartException(
                CartErrorCode.CART_VERSION_CONFLICT,
                "Cart was modified concurrently. Please retry."
        );
    }
}

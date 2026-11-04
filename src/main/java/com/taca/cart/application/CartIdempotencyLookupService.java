package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.persistence.document.CartIdempotencyIdDocument;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import com.taca.cart.persistence.repository.CartIdempotencyMongoRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

@Service
public class CartIdempotencyLookupService {
    private final CartIdempotencyMongoRepository repository;
    private final CartPersistenceMapper mapper;
    private final Clock clock;

    public CartIdempotencyLookupService(CartIdempotencyMongoRepository repository,
                                        CartPersistenceMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Optional<CartIdempotencyKey> findReplay(String buyerUserId, String key,
                                                   String requestHash) {
        Objects.requireNonNull(requestHash, "requestHash");
        var id = new CartIdempotencyIdDocument(buyerUserId, key);

        Optional<CartIdempotencyKey> stored;
        try {
            stored = repository.findById(id).map(mapper::toDomain);
        } catch (DataAccessException | MongoException ex) {
            throw new CartException(
                    CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart idempotency storage is unavailable");
        }

        if (stored.isEmpty()) return Optional.empty();

        CartIdempotencyKey record = stored.orElseThrow();
        if (!record.expiresAt().isAfter(clock.instant())) return Optional.empty();

        if (!record.requestHash().equals(requestHash)) {
            throw new CartException(
                    CartErrorCode.CART_IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key was already used with a different request");
        }

        return Optional.of(record);
    }
}
package com.taca.cart.application;

import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.config.CartProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartIdempotencyId;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.document.CartIdempotencyIdDocument;
import com.taca.cart.persistence.document.CartIdempotencyKeyDocument;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import com.taca.cart.persistence.repository.CartIdempotencyMongoRepository;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CartIdempotencyTransactionService {
    private final CartIdempotencyMongoRepository repository;
    private final CartPersistenceMapper mapper;
    private final MongoOperations mongoOperations;
    private final CartMutationService mutationService;
    private final CartRequestHasher hasher;
    private final CartIdempotencyLookupService lookupService;
    private final CartIdempotencyRetryPolicy retryPolicy;
    private final CartProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public CartIdempotencyTransactionService(
            CartIdempotencyMongoRepository repository,
            CartPersistenceMapper mapper,
            MongoOperations mongoOperations,
            CartMutationService mutationService,
            CartRequestHasher hasher,
            CartIdempotencyLookupService lookupService,
            CartIdempotencyRetryPolicy retryPolicy,
            CartProperties properties,
            Clock clock,
            MongoTransactionManager transactionManager) {
        this.repository = repository;
        this.mapper = mapper;
        this.mongoOperations = mongoOperations;
        this.mutationService = mutationService;
        this.hasher = hasher;
        this.lookupService = lookupService;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public CartIdempotencyKey execute(String buyerUserId, String key,
                                      AddCartItemRequest request,
                                      ValidatedSkuSnapshot sku,
                                      String requestId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(requestId, "requestId");

        if (!sku.productId().equals(request.productId())
                || !sku.skuId().equals(request.skuId())) {
            throw new IllegalArgumentException("Validated SKU does not match request");
        }

        String hash = hasher.hash(request);
        CartIdempotencyId id = new CartIdempotencyId(buyerUserId, key);

        for (int attempt = 0; attempt <= properties.writeRetryMax(); attempt++) {
            try {
                return Objects.requireNonNull(
                        transaction.execute(status ->
                                executeInsideTransaction(id, hash, request, sku, requestId)),
                        "Transaction returned null");
            } catch (RuntimeException ex) {
                var decision = retryPolicy.classify(ex);

                if (decision == CartIdempotencyRetryPolicy.Decision.UNKNOWN_COMMIT) {
                    return resolveUnknownCommit(id, hash);
                }

                if (decision == CartIdempotencyRetryPolicy.Decision.RETRY_TRANSACTION
                        && attempt < properties.writeRetryMax()) {
                    backoff(attempt);
                    continue;
                }

                if (ex instanceof CartException cartException) {
                    throw cartException;
                }

                if (decision == CartIdempotencyRetryPolicy.Decision.RETRY_TRANSACTION
                        || retryPolicy.isStorageFailure(ex)) {
                    throw new CartException(
                            CartErrorCode.CART_STORAGE_UNAVAILABLE,
                            "Cart transaction storage is unavailable");
                }

                throw ex;
            }
        }

        throw new CartException(
                CartErrorCode.CART_VERSION_CONFLICT,
                "Cart transaction retry limit exceeded");
    }

    private CartIdempotencyKey resolveUnknownCommit(
            CartIdempotencyId id, String hash) {
        try {
            var replay = lookupService.findReplay(
                    id.buyerUserId(), id.key(), hash);

            if (replay.isPresent()) return replay.orElseThrow();
        } catch (CartException ex) {
            if (ex.getCode() == CartErrorCode.CART_IDEMPOTENCY_CONFLICT) {
                throw ex;
            }
        }

        throw new CartException(
                CartErrorCode.CART_STORAGE_UNAVAILABLE,
                "Cart commit outcome is unknown; retry only with the same Idempotency-Key");
    }

    private CartIdempotencyKey executeInsideTransaction(
            CartIdempotencyId id, String hash,
            AddCartItemRequest request, ValidatedSkuSnapshot sku,
            String requestId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var documentId = new CartIdempotencyIdDocument(id.buyerUserId(), id.key());
        var existing = repository.findById(documentId).map(mapper::toDomain);

        if (existing.isPresent()) {
            CartIdempotencyKey saved = existing.orElseThrow();

            if (saved.expiresAt().isAfter(now)) {
                if (!saved.requestHash().equals(hash)) {
                    throw new CartException(
                            CartErrorCode.CART_IDEMPOTENCY_CONFLICT,
                            "Idempotency-Key was already used with a different request");
                }
                return saved;
            }
        }

        Cart cart = mutationService.addItem(
                id.buyerUserId(), sku, request.quantity());

        CartItem item = cart.items().stream()
                .filter(candidate -> candidate.skuId().equals(sku.skuId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Mutated SKU not found"));

        Map<String, Object> response = Map.of(
                "data", Map.of(
                        "cart_id", cart.id(),
                        "item_id", item.itemId(),
                        "quantity", item.quantity(),
                        "line_total", Math.multiplyExact(
                                item.unitPriceSnapshot(), item.quantity())),
                "meta", Map.of("request_id", requestId));

        CartIdempotencyKey record = new CartIdempotencyKey(
                id, hash, response, 201,
                now.plus(properties.idempotencyTtl()), now);

        if (existing.isEmpty()) {
            repository.insert(mapper.toDocument(record));
        } else {
            replaceExpiredKey(record, now);
        }

        return record;
    }

    private void replaceExpiredKey(CartIdempotencyKey record, Instant now) {
        Query query = Query.query(Criteria.where("_id.buyer_user_id")
                .is(record.id().buyerUserId())
                .and("_id.key").is(record.id().key())
                .and("expires_at").lte(now));

        Update update = new Update()
                .set("request_hash", record.requestHash())
                .set("response", record.response())
                .set("response_status", record.responseStatus())
                .set("expires_at", record.expiresAt())
                .set("created_at", record.createdAt());

        var result = mongoOperations.updateFirst(
                query, update, CartIdempotencyKeyDocument.class);

        if (result.getMatchedCount() != 1L) {
            throw new CartException(
                    CartErrorCode.CART_VERSION_CONFLICT,
                    "Idempotency key changed during transaction");
        }
    }

    private void backoff(int attempt) {
        long baseMillis = Math.min(160L, 20L * (1L << Math.min(attempt, 3)));
        long delayMillis = baseMillis + ThreadLocalRandom.current().nextLong(11L);

        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CartException(
                    CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart transaction retry was interrupted");
        }
    }
}

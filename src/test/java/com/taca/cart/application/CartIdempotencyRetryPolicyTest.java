package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionSystemException;

import static com.taca.cart.application.CartIdempotencyRetryPolicy.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

class CartIdempotencyRetryPolicyTest {
    private final CartIdempotencyRetryPolicy policy = new CartIdempotencyRetryPolicy();

    @Test
    void shouldRetryTransientTransactionError() {
        MongoException mongo = new MongoException(112, "Write conflict");
        mongo.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

        assertEquals(RETRY_TRANSACTION, policy.classify(mongo));
    }

    @Test
    void shouldRetryWrappedTransientTransactionError() {
        MongoException mongo = new MongoException(112, "Write conflict");
        mongo.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        var wrapped = new DataAccessResourceFailureException("Mongo failed", mongo);

        assertEquals(RETRY_TRANSACTION, policy.classify(wrapped));
        assertTrue(policy.isStorageFailure(wrapped));
    }

    @Test
    void shouldRetryDuplicateKey() {
        assertEquals(RETRY_TRANSACTION,
                policy.classify(new DuplicateKeyException("Duplicate key")));
    }

    @Test
    void shouldRetryMongoDuplicateKeyCode() {
        assertEquals(RETRY_TRANSACTION,
                policy.classify(new MongoException(11000, "Duplicate key")));
    }

    @Test
    void shouldRetryCartVersionConflict() {
        var exception = new CartException(
                CartErrorCode.CART_VERSION_CONFLICT, "CAS failed");

        assertEquals(RETRY_TRANSACTION, policy.classify(exception));
    }

    @Test
    void shouldNotRetryBusinessConflict() {
        var exception = new CartException(
                CartErrorCode.CART_IDEMPOTENCY_CONFLICT, "Different request hash");

        assertEquals(DO_NOT_RETRY, policy.classify(exception));
    }

    @Test
    void shouldNeverRetryUnknownCommitAsNewTransaction() {
        MongoException mongo = new MongoException(91, "Commit outcome unknown");
        mongo.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);

        assertEquals(UNKNOWN_COMMIT, policy.classify(mongo));
    }

    @Test
    void shouldDetectUnknownCommitInsideSpringException() {
        MongoException mongo = new MongoException(91, "Commit outcome unknown");
        mongo.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
        mongo.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

        var wrapped = new TransactionSystemException("Commit failed", mongo);

        assertEquals(UNKNOWN_COMMIT, policy.classify(wrapped));
    }

    @Test
    void shouldNotRetryUnlabelledMongoFailure() {
        MongoException mongo = new MongoException(6, "MongoDB unavailable");

        assertEquals(DO_NOT_RETRY, policy.classify(mongo));
        assertTrue(policy.isStorageFailure(mongo));
    }

    @Test
    void shouldNotTreatProgrammingErrorAsStorageFailure() {
        var exception = new IllegalArgumentException("Invalid input");

        assertEquals(DO_NOT_RETRY, policy.classify(exception));
        assertFalse(policy.isStorageFailure(exception));
    }
}

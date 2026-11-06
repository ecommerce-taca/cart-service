package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@Component
public class CartIdempotencyRetryPolicy {
    public enum Decision {
        RETRY_TRANSACTION,
        UNKNOWN_COMMIT,
        DO_NOT_RETRY
    }

    public Decision classify(Throwable failure) {
        boolean retryable = false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Throwable current = failure;
             current != null && visited.add(current);
             current = current.getCause()) {

            if (current instanceof MongoException mongo
                    && mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                return Decision.UNKNOWN_COMMIT;
            }

            if (current instanceof MongoException mongo
                    && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                retryable = true;
            }

            if (current instanceof DuplicateKeyException) {
                retryable = true;
            }

            if (current instanceof MongoException mongo
                    && (mongo.getCode() == 11000 || mongo.getCode() == 11001)) {
                retryable = true;
            }

            if (current instanceof CartException cart
                    && cart.getCode() == CartErrorCode.CART_VERSION_CONFLICT) {
                retryable = true;
            }
        }

        return retryable ? Decision.RETRY_TRANSACTION : Decision.DO_NOT_RETRY;
    }

    public boolean isStorageFailure(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Throwable current = failure;
             current != null && visited.add(current);
             current = current.getCause()) {

            if (current instanceof MongoException
                    || current instanceof DataAccessException
                    || current instanceof TransactionException) {
                return true;
            }
        }

        return false;
    }
}

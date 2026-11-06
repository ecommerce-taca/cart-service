package com.taca.cart.config;

import com.mongodb.MongoException;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class MongoCommitRetryExecutor {
    private static final int MAX_ATTEMPTS = 3;

    public void execute(Runnable commitAction) {
        Objects.requireNonNull(commitAction, "commitAction");
        MongoException unknownOutcome = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                commitAction.run();
                return;
            } catch (MongoException ex) {
                boolean unknown = ex.hasErrorLabel(
                        MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);

                if (!unknown) {
                    if (unknownOutcome != null) {
                        unknownOutcome.addSuppressed(ex);
                        throw unknownOutcome;
                    }
                    throw ex;
                }

                unknownOutcome = ex;
                if (attempt == MAX_ATTEMPTS) throw ex;

                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    ex.addSuppressed(interrupted);
                    throw ex;
                }
            }
        }
    }
}

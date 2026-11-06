package com.taca.cart.config;

import com.mongodb.MongoException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MongoCommitRetryExecutorTest {
    private final MongoCommitRetryExecutor executor = new MongoCommitRetryExecutor();

    @Test
    void shouldCommitSuccessfullyOnFirstAttempt() {
        AtomicInteger attempts = new AtomicInteger();

        executor.execute(attempts::incrementAndGet);

        assertEquals(1, attempts.get());
    }

    @Test
    void shouldRetrySameCommitAfterUnknownResult() {
        AtomicInteger attempts = new AtomicInteger();

        executor.execute(() -> {
            if (attempts.incrementAndGet() == 1) throw unknownCommit();
        });

        assertEquals(2, attempts.get());
    }

    @Test
    void shouldSucceedOnThirdCommitAttempt() {
        AtomicInteger attempts = new AtomicInteger();

        executor.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw unknownCommit();
        });

        assertEquals(3, attempts.get());
    }

    @Test
    void shouldStopAfterThreeUnknownCommitResults() {
        AtomicInteger attempts = new AtomicInteger();

        MongoException ex = assertThrows(MongoException.class,
                () -> executor.execute(() -> {
                    attempts.incrementAndGet();
                    throw unknownCommit();
                }));

        assertTrue(ex.hasErrorLabel(
                MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL));
        assertEquals(3, attempts.get());
    }

    @Test
    void shouldNotRetryUnlabelledMongoFailure() {
        AtomicInteger attempts = new AtomicInteger();

        MongoException ex = assertThrows(MongoException.class,
                () -> executor.execute(() -> {
                    attempts.incrementAndGet();
                    throw new MongoException(6, "MongoDB unavailable");
                }));

        assertEquals(6, ex.getCode());
        assertEquals(1, attempts.get());
    }

    @Test
    void shouldNotRetryTransientTransactionErrorAsCommit() {
        AtomicInteger attempts = new AtomicInteger();

        MongoException ex = new MongoException(112, "Write conflict");
        ex.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

        assertThrows(MongoException.class, () -> executor.execute(() -> {
            attempts.incrementAndGet();
            throw ex;
        }));

        assertEquals(1, attempts.get());
    }

    @Test
    void shouldPreserveUnknownOutcomeWhenLaterCommitFailsDifferently() {
        AtomicInteger attempts = new AtomicInteger();
        MongoException unknown = unknownCommit();
        MongoException other = new MongoException(251, "NoSuchTransaction");

        MongoException thrown = assertThrows(MongoException.class,
                () -> executor.execute(() -> {
                    if (attempts.incrementAndGet() == 1) throw unknown;
                    throw other;
                }));

        assertSame(unknown, thrown);
        assertEquals(2, attempts.get());
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(other, thrown.getSuppressed()[0]);
    }

    private MongoException unknownCommit() {
        MongoException ex = new MongoException(91, "Unknown commit outcome");
        ex.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
        return ex;
    }
}

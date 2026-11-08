package com.taca.cart.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static com.taca.cart.observability.CartMetrics.CasCause.*;
import static com.taca.cart.observability.CartMetrics.MutationResult.*;
import static org.junit.jupiter.api.Assertions.*;

class CartMetricsTest {
    private SimpleMeterRegistry registry;
    private CartMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CartMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void shouldRegisterFixedMutationResults() {
        for (CartMetrics.MutationResult result :
                CartMetrics.MutationResult.values()) {
            String label = result.name().toLowerCase();

            assertNotNull(registry.get("cart.mutations")
                    .tag("result", label).counter());

            assertNotNull(registry.get("cart.mutation.duration")
                    .tag("result", label).timer());
        }
    }

    @Test
    void shouldCountMutationsAndRecordDuration() {
        metrics.recordMutation(CREATED, TimeUnit.MILLISECONDS.toNanos(5));
        metrics.recordMutation(UPDATED, TimeUnit.MILLISECONDS.toNanos(8));
        metrics.recordMutation(UPDATED, TimeUnit.MILLISECONDS.toNanos(12));
        metrics.recordMutation(NOOP, 0L);
        metrics.recordMutation(FAILED, TimeUnit.MILLISECONDS.toNanos(2));

        assertEquals(1.0, mutationCount("created"));
        assertEquals(2.0, mutationCount("updated"));
        assertEquals(1.0, mutationCount("noop"));
        assertEquals(1.0, mutationCount("failed"));

        var timer = registry.get("cart.mutation.duration")
                .tag("result", "updated").timer();

        assertEquals(2L, timer.count());
        assertEquals(20.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void shouldCountVersionAndDuplicateKeyConflictsSeparately() {
        metrics.recordConflict(VERSION);
        metrics.recordConflict(VERSION);
        metrics.recordConflict(DUPLICATE_KEY);

        assertEquals(2.0, conflictCount("version"));
        assertEquals(1.0, conflictCount("duplicate_key"));
    }

    @Test
    void shouldCountActualRetriesSeparatelyFromConflicts() {
        metrics.recordConflict(VERSION);
        metrics.recordRetry(VERSION);
        metrics.recordConflict(VERSION);
        metrics.recordRetryExhausted();

        assertEquals(2.0, conflictCount("version"));
        assertEquals(1.0, retryCount("version"));
        assertEquals(1.0, registry.get("cart.cas.retry.exhausted")
                .counter().count());
    }

    @Test
    void shouldRejectNegativeElapsedTimeWithoutFailing() {
        assertDoesNotThrow(() -> metrics.recordMutation(FAILED, -1L));

        var timer = registry.get("cart.mutation.duration")
                .tag("result", "failed").timer();

        assertEquals(1L, timer.count());
        assertEquals(0.0, timer.totalTime(TimeUnit.NANOSECONDS));
    }

    private double mutationCount(String result) {
        return registry.get("cart.mutations")
                .tag("result", result).counter().count();
    }

    private double conflictCount(String cause) {
        return registry.get("cart.cas.conflicts")
                .tag("cause", cause).counter().count();
    }

    private double retryCount(String cause) {
        return registry.get("cart.cas.retries")
                .tag("cause", cause).counter().count();
    }
}

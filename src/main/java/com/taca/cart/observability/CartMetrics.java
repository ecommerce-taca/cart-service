package com.taca.cart.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class CartMetrics {
    public enum MutationResult {
        CREATED, UPDATED, NOOP, FAILED
    }

    public enum CasCause {
        VERSION, DUPLICATE_KEY
    }

    private final EnumMap<MutationResult, Counter> mutationCounters =
            new EnumMap<>(MutationResult.class);
    private final EnumMap<MutationResult, Timer> mutationTimers =
            new EnumMap<>(MutationResult.class);
    private final EnumMap<CasCause, Counter> conflictCounters =
            new EnumMap<>(CasCause.class);
    private final EnumMap<CasCause, Counter> retryCounters =
            new EnumMap<>(CasCause.class);
    private final Counter retryExhausted;

    public CartMetrics(MeterRegistry registry) {
        for (MutationResult result : MutationResult.values()) {
            String label = result.name().toLowerCase(Locale.ROOT);

            mutationCounters.put(result, Counter.builder("cart.mutations")
                    .description("Cart persistence mutation calls")
                    .tag("result", label)
                    .register(registry));

            mutationTimers.put(result, Timer.builder("cart.mutation.duration")
                    .description("Cart persistence mutation duration")
                    .tag("result", label)
                    .register(registry));
        }

        for (CasCause cause : CasCause.values()) {
            String label = cause.name().toLowerCase(Locale.ROOT);

            conflictCounters.put(cause, Counter.builder("cart.cas.conflicts")
                    .description("Cart CAS conflicts")
                    .tag("cause", label)
                    .register(registry));

            retryCounters.put(cause, Counter.builder("cart.cas.retries")
                    .description("Cart CAS retry attempts")
                    .tag("cause", label)
                    .register(registry));
        }

        retryExhausted = Counter.builder("cart.cas.retry.exhausted")
                .description("Cart CAS retry budgets exhausted")
                .register(registry);
    }

    public void recordMutation(MutationResult result, long elapsedNanos) {
        mutationCounters.get(result).increment();
        mutationTimers.get(result).record(
                Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordConflict(CasCause cause) {
        conflictCounters.get(cause).increment();
    }

    public void recordRetry(CasCause cause) {
        retryCounters.get(cause).increment();
    }

    public void recordRetryExhausted() {
        retryExhausted.increment();
    }
}

package com.taca.cart.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

public final class TraceContextTasks {
    private TraceContextTasks() {}

    public static <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task");

        Context capturedContext = Context.current();
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();

            try (Scope ignored = capturedContext.makeCurrent()) {
                restore(capturedMdc);
                return task.call();
            } finally {
                restore(previousMdc);
            }
        };
    }

    private static void restore(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(values);
        }
    }
}

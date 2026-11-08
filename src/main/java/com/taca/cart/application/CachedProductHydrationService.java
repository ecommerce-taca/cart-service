package com.taca.cart.application;

import com.taca.cart.config.ProductProperties;
import com.taca.cart.integrations.ProductHydrationClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CachedProductHydrationService {
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final ProductHydrationClient client;
    private final Clock clock;
    private final Duration ttl;
    private final Duration timeout;
    private final Semaphore permits;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<ProductHydrationResult>> inFlight =
            new ConcurrentHashMap<>();
    private final Object evictionLock = new Object();

    public CachedProductHydrationService(ProductHydrationClient client,
                                         ProductProperties properties,
                                         Clock clock) {
        this.client = java.util.Objects.requireNonNull(client);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.ttl = java.util.Objects.requireNonNull(properties.cacheTtl());
        this.timeout = java.util.Objects.requireNonNull(properties.timeout());

        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Product cache TTL must be positive");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Product timeout must be positive");
        }
        if (properties.maxConcurrency() < 1) {
            throw new IllegalArgumentException("Product max concurrency must be positive");
        }

        this.permits = new Semaphore(properties.maxConcurrency(), true);
    }

    public ProductHydrationResult getProduct(String productId) {
        if (productId == null || productId.isBlank()) {
            return ProductHydrationResult.stale();
        }

        CacheEntry current = cache.get(productId);
        if (isValid(current)) return current.result();

        CompletableFuture<ProductHydrationResult> promise = new CompletableFuture<>();
        CompletableFuture<ProductHydrationResult> running =
                inFlight.putIfAbsent(productId, promise);

        if (running != null) return await(running);

        try {
            CacheEntry refreshed = cache.get(productId);
            if (isValid(refreshed)) {
                promise.complete(refreshed.result());
                return refreshed.result();
            }

            ProductHydrationResult result = fetchWithLimit(productId);

            if (cacheable(result)) {
                cache.put(productId, new CacheEntry(result, clock.instant().plus(ttl)));
                evictIfNecessary();
            } else {
                cache.remove(productId);
            }

            promise.complete(result);
            return result;
        } finally {
            if (!promise.isDone()) promise.complete(ProductHydrationResult.stale());
            inFlight.remove(productId, promise);
        }
    }

    private ProductHydrationResult fetchWithLimit(String productId) {
        if (!permits.tryAcquire()) return ProductHydrationResult.stale();

        try {
            ProductHydrationResult result = client.fetchProduct(productId);
            return result == null ? ProductHydrationResult.stale() : result;
        } catch (RuntimeException ex) {
            return ProductHydrationResult.stale();
        } finally {
            permits.release();
        }
    }

    private ProductHydrationResult await(
            CompletableFuture<ProductHydrationResult> running) {
        try {
            return running.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ProductHydrationResult.stale();
        } catch (ExecutionException | TimeoutException ex) {
            return ProductHydrationResult.stale();
        }
    }

    private boolean cacheable(ProductHydrationResult result) {
        return result.state() == ProductHydrationResult.State.FOUND
                || result.state() == ProductHydrationResult.State.UNAVAILABLE;
    }

    private boolean isValid(CacheEntry entry) {
        return entry != null && clock.instant().isBefore(entry.expiresAt());
    }

    private void evictIfNecessary() {
        if (cache.size() <= MAX_CACHE_ENTRIES) return;

        synchronized (evictionLock) {
            Instant now = clock.instant();
            cache.entrySet().removeIf(entry ->
                    !now.isBefore(entry.getValue().expiresAt()));

            int excess = cache.size() - MAX_CACHE_ENTRIES;
            if (excess <= 0) return;

            for (String key : cache.keySet()) {
                if (excess <= 0) break;
                if (cache.remove(key) != null) excess--;
            }
        }
    }

    private record CacheEntry(
            ProductHydrationResult result,
            Instant expiresAt
    ) {}
}

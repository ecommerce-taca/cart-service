package com.taca.cart.application;

import com.taca.cart.config.ProductProperties;
import com.taca.cart.integrations.ProductHydrationClient;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CachedProductHydrationServiceTest {
    private static final String PRODUCT = "product-001";

    private final ProductHydrationClient client = mock(ProductHydrationClient.class);
    private final ManualClock clock = new ManualClock();

    @Test
    void shouldCacheFoundResultWithinThirtySeconds() {
        ProductHydrationResult found = found(PRODUCT);
        when(client.fetchProduct(PRODUCT)).thenReturn(found);

        var service = service(10);

        assertSame(found, service.getProduct(PRODUCT));
        assertSame(found, service.getProduct(PRODUCT));
        clock.advance(Duration.ofSeconds(29));
        assertSame(found, service.getProduct(PRODUCT));

        verify(client, times(1)).fetchProduct(PRODUCT);
    }

    @Test
    void shouldRefreshProductWhenCacheExpires() {
        ProductHydrationResult first = found(PRODUCT);
        ProductHydrationResult second = foundWithPrice(PRODUCT, 199000L);
        when(client.fetchProduct(PRODUCT)).thenReturn(first, second);

        var service = service(10);

        assertSame(first, service.getProduct(PRODUCT));
        clock.advance(Duration.ofSeconds(30));
        assertSame(second, service.getProduct(PRODUCT));
        assertEquals(199000L,
                service.getProduct(PRODUCT).skus().get("sku-001").unitPrice());

        verify(client, times(2)).fetchProduct(PRODUCT);
    }

    @Test
    void shouldCacheUnavailableResult() {
        when(client.fetchProduct(PRODUCT))
                .thenReturn(ProductHydrationResult.unavailable());

        var service = service(10);

        assertEquals(ProductHydrationResult.State.UNAVAILABLE,
                service.getProduct(PRODUCT).state());
        assertEquals(ProductHydrationResult.State.UNAVAILABLE,
                service.getProduct(PRODUCT).state());

        verify(client, times(1)).fetchProduct(PRODUCT);
    }

    @Test
    void shouldRefreshUnavailableResultAfterExpiry() {
        when(client.fetchProduct(PRODUCT)).thenReturn(
                ProductHydrationResult.unavailable(), found(PRODUCT));

        var service = service(10);

        assertEquals(ProductHydrationResult.State.UNAVAILABLE,
                service.getProduct(PRODUCT).state());

        clock.advance(Duration.ofSeconds(30));

        assertEquals(ProductHydrationResult.State.FOUND,
                service.getProduct(PRODUCT).state());

        verify(client, times(2)).fetchProduct(PRODUCT);
    }

    @Test
    void shouldNotCacheStaleResult() {
        when(client.fetchProduct(PRODUCT)).thenReturn(
                ProductHydrationResult.stale(), found(PRODUCT));

        var service = service(10);

        assertEquals(ProductHydrationResult.State.STALE,
                service.getProduct(PRODUCT).state());
        assertEquals(ProductHydrationResult.State.FOUND,
                service.getProduct(PRODUCT).state());
        assertEquals(ProductHydrationResult.State.FOUND,
                service.getProduct(PRODUCT).state());

        verify(client, times(2)).fetchProduct(PRODUCT);
    }

    @Test
    void shouldMaintainIndependentEntriesForDifferentProducts() {
        when(client.fetchProduct("product-001")).thenReturn(found("product-001"));
        when(client.fetchProduct("product-002")).thenReturn(found("product-002"));

        var service = service(10);

        assertEquals("product-001",
                service.getProduct("product-001").skus().get("sku-001").productId());
        assertEquals("product-002",
                service.getProduct("product-002").skus().get("sku-001").productId());

        service.getProduct("product-001");
        service.getProduct("product-002");

        verify(client, times(1)).fetchProduct("product-001");
        verify(client, times(1)).fetchProduct("product-002");
    }

    @Test
    void shouldSingleFlightConcurrentRequestsForSameProduct() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(client.fetchProduct(PRODUCT)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Product fetch timed out");
            }
            return found(PRODUCT);
        });

        var service = service(10);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            Future<ProductHydrationResult> leader =
                    executor.submit(() -> service.getProduct(PRODUCT));

            assertTrue(entered.await(3, TimeUnit.SECONDS));

            List<Future<ProductHydrationResult>> followers = new ArrayList<>();

            for (int i = 0; i < 6; i++) {
                followers.add(executor.submit(() -> service.getProduct(PRODUCT)));
            }

            release.countDown();

            assertEquals(ProductHydrationResult.State.FOUND,
                    leader.get(10, TimeUnit.SECONDS).state());

            for (Future<ProductHydrationResult> follower : followers) {
                assertEquals(ProductHydrationResult.State.FOUND,
                        follower.get(10, TimeUnit.SECONDS).state());
            }

            verify(client, times(1)).fetchProduct(PRODUCT);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldLimitConcurrentProductCallsAndFallbackWhenSaturated() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        when(client.fetchProduct(anyString())).thenAnswer(invocation -> {
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            entered.countDown();

            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Product fetch timed out");
                }
                return found(invocation.getArgument(0));
            } finally {
                active.decrementAndGet();
            }
        });

        var service = service(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ProductHydrationResult> first =
                    executor.submit(() -> service.getProduct("product-001"));
            Future<ProductHydrationResult> second =
                    executor.submit(() -> service.getProduct("product-002"));

            assertTrue(entered.await(3, TimeUnit.SECONDS));

            ProductHydrationResult overloaded = service.getProduct("product-003");

            assertEquals(ProductHydrationResult.State.STALE, overloaded.state());
            verify(client, never()).fetchProduct("product-003");

            release.countDown();

            assertEquals(ProductHydrationResult.State.FOUND,
                    first.get(10, TimeUnit.SECONDS).state());
            assertEquals(ProductHydrationResult.State.FOUND,
                    second.get(10, TimeUnit.SECONDS).state());

            assertEquals(2, maximum.get());

            assertEquals(ProductHydrationResult.State.FOUND,
                    service.getProduct("product-003").state());

            verify(client, times(1)).fetchProduct("product-003");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReleasePermitAndNotCacheUnexpectedClientFailure() {
        when(client.fetchProduct(PRODUCT))
                .thenThrow(new IllegalStateException("HTTP adapter failed"))
                .thenReturn(found(PRODUCT));

        var service = service(1);

        assertEquals(ProductHydrationResult.State.STALE,
                service.getProduct(PRODUCT).state());

        assertEquals(ProductHydrationResult.State.FOUND,
                service.getProduct(PRODUCT).state());

        verify(client, times(2)).fetchProduct(PRODUCT);
    }

    @Test
    void shouldTimeoutFollowerWithoutStartingDuplicateFetch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(client.fetchProduct(PRODUCT)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Product fetch timed out");
            }
            return found(PRODUCT);
        });

        var service = service(2, Duration.ofMillis(100));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<ProductHydrationResult> leader =
                    executor.submit(() -> service.getProduct(PRODUCT));

            assertTrue(entered.await(3, TimeUnit.SECONDS));

            ProductHydrationResult follower = service.getProduct(PRODUCT);

            assertEquals(ProductHydrationResult.State.STALE, follower.state());
            verify(client, times(1)).fetchProduct(PRODUCT);

            release.countDown();

            assertEquals(ProductHydrationResult.State.FOUND,
                    leader.get(10, TimeUnit.SECONDS).state());
            assertEquals(ProductHydrationResult.State.FOUND,
                    service.getProduct(PRODUCT).state());

            verify(client, times(1)).fetchProduct(PRODUCT);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReturnStaleWithoutFetchForBlankProductId() {
        var service = service(10);

        assertEquals(ProductHydrationResult.State.STALE,
                service.getProduct(null).state());
        assertEquals(ProductHydrationResult.State.STALE,
                service.getProduct(" ").state());

        verifyNoInteractions(client);
    }

    @Test
    void shouldRejectInvalidCacheConfiguration() {
        ProductProperties invalidTtl = new ProductProperties(
                Duration.ofSeconds(2), Duration.ZERO, 10);

        ProductProperties invalidTimeout = new ProductProperties(
                Duration.ZERO, Duration.ofSeconds(30), 10);

        ProductProperties invalidConcurrency = new ProductProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(30), 0);

        assertThrows(IllegalArgumentException.class,
                () -> new CachedProductHydrationService(client, invalidTtl, clock));

        assertThrows(IllegalArgumentException.class,
                () -> new CachedProductHydrationService(client, invalidTimeout, clock));

        assertThrows(IllegalArgumentException.class,
                () -> new CachedProductHydrationService(client, invalidConcurrency, clock));
    }

    private CachedProductHydrationService service(int maxConcurrency) {
        return service(maxConcurrency, Duration.ofSeconds(2));
    }

    private CachedProductHydrationService service(int maxConcurrency,
                                                  Duration timeout) {
        ProductProperties properties = new ProductProperties(
                timeout, Duration.ofSeconds(30), maxConcurrency);
        return new CachedProductHydrationService(client, properties, clock);
    }

    private ProductHydrationResult found(String productId) {
        return foundWithPrice(productId, 249000L);
    }

    private ProductHydrationResult foundWithPrice(String productId, long price) {
        return ProductHydrationResult.found(Map.of(
                "sku-001", new ValidatedSkuSnapshot(
                        productId, "sku-001", "shop-001",
                        "Cotton jacket", "Black / L", price)));
    }

    private static final class ManualClock extends Clock {
        private final AtomicReference<Instant> current =
                new AtomicReference<>(Instant.parse("2026-11-07T00:00:00Z"));

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}

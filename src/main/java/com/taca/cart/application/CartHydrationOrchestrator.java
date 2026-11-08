package com.taca.cart.application;

import com.taca.cart.api.dto.AvailabilityStatus;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.config.CartProperties;
import com.taca.cart.config.InventoryProperties;
import com.taca.cart.config.ProductProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.InventoryAvailabilityClient;
import com.taca.cart.observability.CartDependencyMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class CartHydrationOrchestrator {
    private final CachedProductHydrationService productService;
    private final CartProductResponseHydrator productHydrator;
    private final InventoryAvailabilityClient inventoryClient;
    private final CartInventoryResponseHydrator inventoryHydrator;
    private final long budgetNanos;
    private final long productTimeoutNanos;
    private final long inventoryTimeoutNanos;
    private final int productConcurrency;
    private final int inventoryBatchSize;
    private final CartDependencyMetrics metrics;


    public CartHydrationOrchestrator(
            CachedProductHydrationService productService,
            CartProductResponseHydrator productHydrator,
            InventoryAvailabilityClient inventoryClient,
            CartInventoryResponseHydrator inventoryHydrator,
            CartProperties cartProperties,
            ProductProperties productProperties,
            InventoryProperties inventoryProperties) {
        this(productService, productHydrator, inventoryClient, inventoryHydrator,
                cartProperties, productProperties, inventoryProperties, null);
    }

    @Autowired
    public CartHydrationOrchestrator(
            CachedProductHydrationService productService,
            CartProductResponseHydrator productHydrator,
            InventoryAvailabilityClient inventoryClient,
            CartInventoryResponseHydrator inventoryHydrator,
            CartProperties cartProperties,
            ProductProperties productProperties,
            InventoryProperties inventoryProperties,
            CartDependencyMetrics metrics) {
        this.productService = Objects.requireNonNull(productService);
        this.productHydrator = Objects.requireNonNull(productHydrator);
        this.inventoryClient = Objects.requireNonNull(inventoryClient);
        this.inventoryHydrator = Objects.requireNonNull(inventoryHydrator);
        this.metrics = metrics;

        this.budgetNanos = positiveNanos(cartProperties.hydrateTimeout());
        this.productTimeoutNanos = positiveNanos(productProperties.timeout());
        this.inventoryTimeoutNanos = positiveNanos(inventoryProperties.timeout());
        this.productConcurrency = Math.min(10, productProperties.maxConcurrency());
        this.inventoryBatchSize = inventoryProperties.maxBatchSize();

        if (productConcurrency < 1 || inventoryBatchSize < 1
                || inventoryBatchSize > 100) {
            throw new IllegalArgumentException(
                    "Invalid hydration concurrency or batch size");
        }
    }


    public CartResponse hydrate(Optional<Cart> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.isEmpty()) return CartResponse.empty();

        Cart cart = snapshot.orElseThrow();
        if (cart.items().isEmpty()) {
            return CartResponse.fromSnapshot(snapshot);
        }

        long started = System.nanoTime();
        long inventoryReserve = Math.min(inventoryTimeoutNanos, budgetNanos / 3);
        long productBudget = Math.min(
                productTimeoutNanos, budgetNanos - inventoryReserve);

        Map<String, ProductHydrationResult> products =
                fetchProducts(cart, started, productBudget);

        CartResponse productResponse = productHydrator.hydrate(snapshot, products);

        Set<String> validSkuIds = productResponse.items().stream()
                .filter(item -> item.availabilityStatus() == AvailabilityStatus.UNKNOWN)
                .map(item -> item.skuId())
                .collect(Collectors.toCollection(LinkedHashSet::new));


        Map<String, InventoryStock> inventory =
                fetchInventory(validSkuIds, started);

        CartResponse response = inventoryHydrator.hydrate(productResponse, products, inventory);

        if (metrics != null) {
            metrics.recordHydrationItems(response);
        }

        return response;
    }

    private Map<String, ProductHydrationResult> fetchProducts(
            Cart cart, long started, long productBudget) {
        List<String> productIds = cart.items().stream()
                .map(CartItem::productId).distinct().toList();

        if (productIds.isEmpty() || productBudget <= 0) return Map.of();

        int workers = Math.min(productConcurrency, productIds.size());
        ExecutorService executor = Executors.newFixedThreadPool(
                workers, Thread.ofVirtual().name("cart-product-hydrate-", 0).factory());

        ExecutorCompletionService<ProductFetch> completion =
                new ExecutorCompletionService<>(executor);
        List<Future<ProductFetch>> submitted = new ArrayList<>();
        Map<String, ProductHydrationResult> results = new HashMap<>();

        try {
            for (String productId : productIds) {
                submitted.add(completion.submit(() ->
                        new ProductFetch(productId, productService.getProduct(productId))));
            }

            int completed = 0;
            while (completed < submitted.size()) {
                long remaining = productBudget - (System.nanoTime() - started);
                if (remaining <= 0) break;

                Future<ProductFetch> future =
                        completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;

                completed++;
                try {
                    ProductFetch result = future.get();
                    if (result.value() != null) {
                        results.put(result.productId(), result.value());
                    }
                } catch (ExecutionException | CancellationException ignored) {
                    // Missing Product results become STALE in the response mapper.
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            submitted.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }

        return results;
    }

    private Map<String, InventoryStock> fetchInventory(
            Set<String> skuIds, long started) {
        if (skuIds.isEmpty() || Thread.currentThread().isInterrupted()
                || remainingNanos(started) <= 0) {
            return Map.of();
        }

        List<String> ordered = skuIds.stream().sorted().toList();
        Map<String, InventoryStock> results = new HashMap<>();

        ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("cart-inventory-hydrate-", 0).factory());

        try {
            for (int offset = 0; offset < ordered.size(); offset += inventoryBatchSize) {
                long remaining = Math.min(remainingNanos(started), inventoryTimeoutNanos);
                if (remaining <= 0) break;

                Set<String> batch = Set.copyOf(
                        ordered.subList(offset,
                                Math.min(offset + inventoryBatchSize, ordered.size())));

                Future<Map<String, InventoryStock>> future =
                        executor.submit(() -> inventoryClient.fetchAvailability(batch));

                try {
                    Map<String, InventoryStock> fetched =
                            future.get(remaining, TimeUnit.NANOSECONDS);

                    if (fetched != null) {
                        fetched.forEach((skuId, stock) -> {
                            if (batch.contains(skuId) && stock != null
                                    && skuId.equals(stock.skuId())) {
                                results.put(skuId, stock);
                            }
                        });
                    }
                } catch (TimeoutException ex) {
                    future.cancel(true);
                    break;
                } catch (ExecutionException | CancellationException ignored) {
                    // The affected batch remains UNKNOWN.
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    break;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        return results;
    }

    private long remainingNanos(long started) {
        return budgetNanos - (System.nanoTime() - started);
    }

    private static long positiveNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Hydration timeout must be positive");
        }
        return duration.toNanos();
    }

    private record ProductFetch(
            String productId,
            ProductHydrationResult value
    ) {}
}

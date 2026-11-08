package com.taca.cart.persistence;

import com.mongodb.client.result.UpdateResult;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.config.CartProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.observability.CartMetrics;
import com.taca.cart.persistence.document.CartDocument;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CartCasPersistenceMetricsTest {
    private static final String BUYER = "buyer-001";
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private MongoOperations mongoOperations;
    private CartPersistenceMapper mapper;
    private CartCasPersistence persistence;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        mongoOperations = mock(MongoOperations.class);
        mapper = new CartPersistenceMapper();
        registry = new SimpleMeterRegistry();

        CartProperties properties = new CartProperties(
                100, 1, 999,
                Duration.ofSeconds(3),
                Duration.ofHours(24),
                3);

        persistence = new CartCasPersistence(
                mongoOperations, mapper, properties,
                new CartMetrics(registry));
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void shouldRecordCreatedCart() {
        when(mongoOperations.findOne(
                any(Query.class), eq(CartDocument.class)))
                .thenReturn(null);

        Cart created = persistence.mutate(BUYER, this::candidate);

        assertEquals(1L, created.version());
        assertEquals(1, created.items().size());
        assertMutation("created", 1);
        assertMutation("failed", 0);

        verify(mongoOperations).insert(any(CartDocument.class));
        verify(mongoOperations, never()).updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class));
    }

    @Test
    void shouldRecordSuccessfulUpdate() {
        when(mongoOperations.findOne(
                any(Query.class), eq(CartDocument.class)))
                .thenReturn(mapper.toDocument(emptyCart()));

        when(mongoOperations.updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        Cart updated = persistence.mutate(BUYER, this::candidate);

        assertEquals(2L, updated.version());
        assertEquals(1, updated.items().size());
        assertMutation("updated", 1);
        assertMutation("failed", 0);
        assertCounter("cart.cas.conflicts", "cause", "version", 0);
    }

    @Test
    void shouldRecordNoopWithoutDatabaseUpdate() {
        Cart original = emptyCart();

        when(mongoOperations.findOne(
                any(Query.class), eq(CartDocument.class)))
                .thenReturn(mapper.toDocument(original));

        Cart returned = persistence.mutate(
                BUYER, snapshot -> snapshot.orElseThrow());

        assertEquals(original, returned);
        assertMutation("noop", 1);
        assertMutation("updated", 0);

        verify(mongoOperations, never()).updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class));
    }

    @Test
    void shouldRecordCasConflictAndRetryBeforeSuccess() {
        when(mongoOperations.findOne(
                any(Query.class), eq(CartDocument.class)))
                .thenReturn(mapper.toDocument(emptyCart()));

        when(mongoOperations.updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class)))
                .thenReturn(
                        UpdateResult.acknowledged(0L, 0L, null),
                        UpdateResult.acknowledged(1L, 1L, null));

        Cart result = persistence.mutate(BUYER, this::candidate);

        assertEquals(2L, result.version());
        assertMutation("updated", 1);
        assertCounter("cart.cas.conflicts", "cause", "version", 1);
        assertCounter("cart.cas.retries", "cause", "version", 1);
        assertEquals(0.0, exhausted());

        verify(mongoOperations, times(2)).updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class));
    }

    @Test
    void shouldCountRetryExhaustionOnlyOnce() {
        when(mongoOperations.findOne(
                any(Query.class), eq(CartDocument.class)))
                .thenReturn(mapper.toDocument(emptyCart()));

        when(mongoOperations.updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        CartException exception = assertThrows(CartException.class,
                () -> persistence.mutate(BUYER, this::candidate));

        assertEquals(CartErrorCode.CART_VERSION_CONFLICT,
                exception.getCode());

        assertMutation("failed", 1);
        assertCounter("cart.cas.conflicts", "cause", "version", 4);
        assertCounter("cart.cas.retries", "cause", "version", 3);
        assertEquals(1.0, exhausted());

        verify(mongoOperations, times(4)).updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class));
    }

    @Test
    void shouldRecordStorageFailureWithoutCasRetry() {
        when(mongoOperations.findOne(
                any(Query.class), eq(CartDocument.class)))
                .thenThrow(new DataAccessResourceFailureException(
                        "MongoDB unavailable"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> persistence.mutate(BUYER, this::candidate));

        assertMutation("failed", 1);
        assertCounter("cart.cas.retries", "cause", "version", 0);
        assertCounter("cart.cas.conflicts", "cause", "version", 0);
        assertEquals(0.0, exhausted());

        verify(mongoOperations, never()).updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class));
    }

    @Test
    void shouldRecordDuplicateKeyConflictThenRetry() {
        when(mongoOperations.findOne(
                any(Query.class), eq(CartDocument.class)))
                .thenReturn(null, mapper.toDocument(emptyCart()));

        when(mongoOperations.insert(any(CartDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate cart"));

        when(mongoOperations.updateFirst(
                any(Query.class), any(UpdateDefinition.class),
                eq(CartDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        Cart updated = persistence.mutate(BUYER, this::candidate);

        assertEquals(2L, updated.version());
        assertMutation("updated", 1);
        assertMutation("created", 0);
        assertCounter("cart.cas.conflicts", "cause", "duplicate_key", 1);
        assertCounter("cart.cas.retries", "cause", "duplicate_key", 1);
        assertEquals(0.0, exhausted());
    }

    private Cart candidate(Optional<Cart> snapshot) {
        if (snapshot.isEmpty()) {
            return new Cart("cart-001", BUYER,
                    List.of(item()), 0L, NOW, NOW);
        }

        Cart current = snapshot.orElseThrow();
        return new Cart(current.id(), current.buyerUserId(),
                List.of(item()), current.version(),
                current.createdAt(), current.updatedAt());
    }

    private Cart emptyCart() {
        return new Cart("cart-001", BUYER,
                List.of(), 1L, NOW, NOW);
    }

    private CartItem item() {
        return new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                2, 1L, "Product", "Black / L",
                249000L, true, NOW, NOW);
    }

    private void assertMutation(String result, int expected) {
        assertCounter("cart.mutations", "result", result, expected);

        assertEquals(expected, registry.get("cart.mutation.duration")
                .tag("result", result).timer().count());
    }

    private void assertCounter(
            String name, String tag, String value, int expected) {
        assertEquals((double) expected, registry.get(name)
                .tag(tag, value).counter().count());
    }

    private double exhausted() {
        return registry.get("cart.cas.retry.exhausted")
                .counter().count();
    }
}

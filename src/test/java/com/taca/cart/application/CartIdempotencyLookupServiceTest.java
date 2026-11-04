package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.persistence.document.CartIdempotencyIdDocument;
import com.taca.cart.persistence.document.CartIdempotencyKeyDocument;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import com.taca.cart.persistence.repository.CartIdempotencyMongoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartIdempotencyLookupServiceTest {
    private static final String BUYER = "buyer-001";
    private static final String KEY = "add-item-001";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final CartIdempotencyMongoRepository repository =
            mock(CartIdempotencyMongoRepository.class);
    private final CartPersistenceMapper mapper = new CartPersistenceMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CartIdempotencyLookupService service =
            new CartIdempotencyLookupService(repository, mapper, clock);

    @Test
    void shouldReturnEmptyWhenKeyDoesNotExist() {
        when(repository.findById(id(BUYER, KEY))).thenReturn(Optional.empty());

        Optional<CartIdempotencyKey> result = service.findReplay(BUYER, KEY, HASH);

        assertTrue(result.isEmpty());
        verify(repository).findById(id(BUYER, KEY));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldReplayCommittedResponseWhenHashMatches() {
        when(repository.findById(id(BUYER, KEY)))
                .thenReturn(Optional.of(document(BUYER, KEY, HASH, NOW.plusSeconds(60))));

        CartIdempotencyKey result = service.findReplay(BUYER, KEY, HASH).orElseThrow();

        assertEquals(201, result.responseStatus());
        assertEquals(HASH, result.requestHash());
        assertEquals(BUYER, result.id().buyerUserId());
        assertEquals(KEY, result.id().key());

        Map<?, ?> data = (Map<?, ?>) result.response().get("data");
        Map<?, ?> meta = (Map<?, ?>) result.response().get("meta");

        assertEquals("cart-001", data.get("cart_id"));
        assertEquals("item-001", data.get("item_id"));
        assertEquals(2, data.get("quantity"));
        assertEquals(498000L, data.get("line_total"));
        assertEquals("req-original-001", meta.get("request_id"));

        verify(repository).findById(id(BUYER, KEY));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldRejectActiveKeyWithDifferentHash() {
        when(repository.findById(id(BUYER, KEY)))
                .thenReturn(Optional.of(document(BUYER, KEY, HASH, NOW.plusSeconds(60))));

        CartException ex = assertThrows(CartException.class,
                () -> service.findReplay(BUYER, KEY, OTHER_HASH));

        assertEquals(CartErrorCode.CART_IDEMPOTENCY_CONFLICT, ex.getCode());
    }

    @Test
    void shouldReturnEmptyForExpiredKeyWithMatchingHash() {
        when(repository.findById(id(BUYER, KEY)))
                .thenReturn(Optional.of(document(BUYER, KEY, HASH, NOW.minusSeconds(1))));

        assertTrue(service.findReplay(BUYER, KEY, HASH).isEmpty());
    }

    @Test
    void shouldAllowExpiredKeyWithDifferentHash() {
        when(repository.findById(id(BUYER, KEY)))
                .thenReturn(Optional.of(document(BUYER, KEY, HASH, NOW.minusSeconds(1))));

        assertTrue(service.findReplay(BUYER, KEY, OTHER_HASH).isEmpty());
    }

    @Test
    void shouldTreatExactExpiryTimeAsExpired() {
        when(repository.findById(id(BUYER, KEY)))
                .thenReturn(Optional.of(document(BUYER, KEY, HASH, NOW)));

        assertTrue(service.findReplay(BUYER, KEY, OTHER_HASH).isEmpty());
    }

    @Test
    void shouldScopeLookupByBuyer() {
        when(repository.findById(id(BUYER, KEY)))
                .thenReturn(Optional.of(document(BUYER, KEY, HASH, NOW.plusSeconds(60))));
        when(repository.findById(id("buyer-002", KEY))).thenReturn(Optional.empty());

        assertTrue(service.findReplay(BUYER, KEY, HASH).isPresent());
        assertTrue(service.findReplay("buyer-002", KEY, HASH).isEmpty());

        verify(repository).findById(id(BUYER, KEY));
        verify(repository).findById(id("buyer-002", KEY));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldReturn503WhenSpringDataCannotRead() {
        when(repository.findById(id(BUYER, KEY)))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        CartException ex = assertThrows(CartException.class,
                () -> service.findReplay(BUYER, KEY, HASH));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
    }

    @Test
    void shouldReturn503WhenMongoDriverFails() {
        when(repository.findById(id(BUYER, KEY)))
                .thenThrow(new MongoException(6, "MongoDB connection failed"));

        CartException ex = assertThrows(CartException.class,
                () -> service.findReplay(BUYER, KEY, HASH));

        assertEquals(CartErrorCode.CART_STORAGE_UNAVAILABLE, ex.getCode());
    }

    private CartIdempotencyIdDocument id(String buyer, String key) {
        return new CartIdempotencyIdDocument(buyer, key);
    }

    private CartIdempotencyKeyDocument document(
            String buyer, String key, String hash, Instant expiresAt) {
        Map<String, Object> response = Map.of(
                "data", Map.of(
                        "cart_id", "cart-001",
                        "item_id", "item-001",
                        "quantity", 2,
                        "line_total", 498000L),
                "meta", Map.of("request_id", "req-original-001"));

        return new CartIdempotencyKeyDocument(
                id(buyer, key), hash, response, 201,
                expiresAt, NOW.minusSeconds(7200));
    }
}
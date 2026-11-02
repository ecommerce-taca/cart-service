
package com.taca.cart.persistence;

import com.mongodb.client.result.UpdateResult;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.config.CartProperties;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.persistence.document.CartDocument;
import com.taca.cart.persistence.mapper.CartPersistenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CartCasPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    private MongoOperations mongoOperations;
    private CartCasPersistence persistence;
    private CartPersistenceMapper mapper;

    @BeforeEach
    void setUp() {
        mongoOperations = mock(MongoOperations.class);
        mapper = new CartPersistenceMapper();

        var properties = new CartProperties(
                100, 1, 999,
                Duration.ofSeconds(3),
                Duration.ofHours(24),
                3
        );

        persistence = new CartCasPersistence(mongoOperations, mapper, properties);
    }

    @Test
    void shouldThrowVersionConflictAfterRetryBudgetExhausted() {
        Cart current = sampleCart();

        when(mongoOperations.findOne(any(Query.class), eq(CartDocument.class)))
                .thenReturn(mapper.toDocument(current));

        when(mongoOperations.updateFirst(
                any(Query.class), any(UpdateDefinition.class), eq(CartDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        CartException exception = assertThrows(CartException.class,
                () -> persistence.mutate("buyer-001", snapshot -> addItem(snapshot.orElseThrow())));

        assertEquals(CartErrorCode.CART_VERSION_CONFLICT, exception.getCode());

        verify(mongoOperations, times(4)).updateFirst(
                any(Query.class), any(UpdateDefinition.class), eq(CartDocument.class));
    }

    @Test
    void shouldNotRetryOnStorageFailure() {
        Cart current = sampleCart();

        when(mongoOperations.findOne(any(Query.class), eq(CartDocument.class)))
                .thenReturn(mapper.toDocument(current));

        when(mongoOperations.updateFirst(
                any(Query.class), any(UpdateDefinition.class), eq(CartDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> persistence.mutate("buyer-001", snapshot -> addItem(snapshot.orElseThrow())));

        verify(mongoOperations, times(1)).updateFirst(
                any(Query.class), any(UpdateDefinition.class), eq(CartDocument.class));
    }

    private Cart sampleCart() {
        return new Cart("cart-001", "buyer-001", List.of(), 1L, NOW, NOW);
    }

    private Cart addItem(Cart current) {
        CartItem item = new CartItem(
                "item-001", "product-001", "sku-001", "shop-001",
                1, 1L, "Cotton jacket", "Black / L",
                249000L, true, NOW, NOW
        );

        return new Cart(
                current.id(), current.buyerUserId(), List.of(item),
                current.version(), current.createdAt(), current.updatedAt()
        );
    }
}

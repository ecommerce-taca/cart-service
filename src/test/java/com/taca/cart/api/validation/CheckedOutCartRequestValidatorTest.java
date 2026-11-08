package com.taca.cart.api.validation;

import com.taca.cart.api.dto.CheckedOutCartRequest;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CheckedOutCartRequestValidatorTest {
    private final CheckedOutCartRequestValidator validator =
            new CheckedOutCartRequestValidator();

    @Test
    void shouldAcceptValidSingleItem() {
        CheckedOutCartRequest request = request(
                List.of("item-001"), Map.of("item-001", 3L));

        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void shouldAcceptMultipleItemsWithUnorderedRevisionMap() {
        CheckedOutCartRequest request = request(
                List.of("item-002", "item-001"),
                Map.of("item-001", 3L, "item-002", 1L));

        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void shouldRejectNullRequest() {
        assertInvalid(null);
    }

    @Test
    void shouldRejectInvalidCartId() {
        for (String id : Arrays.asList(
                null, "", " ", "bad cart", "cart/001", "x".repeat(129))) {
            assertInvalid(new CheckedOutCartRequest(
                    id, "group-001",
                    List.of("item-001"), Map.of("item-001", 1L)));
        }
    }

    @Test
    void shouldRejectInvalidCheckoutGroupId() {
        for (String id : Arrays.asList(
                null, "", " ", "bad group", "group/001", "x".repeat(129))) {
            assertInvalid(new CheckedOutCartRequest(
                    "cart-001", id,
                    List.of("item-001"), Map.of("item-001", 1L)));
        }
    }

    @Test
    void shouldRejectMissingOrEmptyItemIds() {
        assertInvalid(request(null, Map.of()));
        assertInvalid(request(List.of(), Map.of()));
    }

    @Test
    void shouldRejectMoreThanHundredItemIds() {
        List<String> itemIds = IntStream.range(0, 101)
                .mapToObj(i -> "item-%03d".formatted(i))
                .toList();

        Map<String, Long> revisions = new LinkedHashMap<>();
        itemIds.forEach(id -> revisions.put(id, 1L));

        assertInvalid(request(itemIds, revisions));
    }

    @Test
    void shouldRejectDuplicateItemIds() {
        assertInvalid(request(
                List.of("item-001", "item-001"),
                Map.of("item-001", 1L)));
    }

    @Test
    void shouldRejectInvalidItemIds() {
        assertInvalid(request(
                List.of("item-001", "bad item"),
                Map.of("item-001", 1L, "bad item", 2L)));

        assertInvalid(request(
                Arrays.asList("item-001", null),
                Map.of("item-001", 1L)));
    }

    @Test
    void shouldRejectNullOrEmptyRevisionMap() {
        assertInvalid(request(List.of("item-001"), null));
        assertInvalid(request(List.of("item-001"), Map.of()));
    }

    @Test
    void shouldRejectMissingRevision() {
        assertInvalid(request(
                List.of("item-001", "item-002"),
                Map.of("item-001", 3L)));
    }

    @Test
    void shouldRejectExtraRevision() {
        assertInvalid(request(
                List.of("item-001"),
                Map.of("item-001", 3L, "item-extra", 1L)));
    }

    @Test
    void shouldRejectWrongRevisionKeyEvenWhenCountsMatch() {
        assertInvalid(request(
                List.of("item-001", "item-002"),
                Map.of("item-001", 3L, "item-003", 2L)));
    }

    @Test
    void shouldRejectZeroNegativeAndNullRevisions() {
        for (Long revision : Arrays.asList(null, 0L, -1L)) {
            Map<String, Long> revisions = new HashMap<>();
            revisions.put("item-001", revision);

            assertInvalid(request(List.of("item-001"), revisions));
        }
    }

    @Test
    void shouldAcceptMaximumHundredDistinctIds() {
        List<String> itemIds = IntStream.range(0, 100)
                .mapToObj(i -> "item-%03d".formatted(i))
                .toList();

        Map<String, Long> revisions = new LinkedHashMap<>();
        itemIds.forEach(id -> revisions.put(id, 1L));

        assertDoesNotThrow(() ->
                validator.validate(request(itemIds, revisions)));
    }

    private CheckedOutCartRequest request(
            List<String> itemIds, Map<String, Long> revisions) {
        return new CheckedOutCartRequest(
                "cart-001", "group-001", itemIds, revisions);
    }

    private void assertInvalid(CheckedOutCartRequest request) {
        CartException ex = assertThrows(
                CartException.class, () -> validator.validate(request));

        assertEquals(CartErrorCode.CART_INVALID_INPUT, ex.getCode());
    }
}

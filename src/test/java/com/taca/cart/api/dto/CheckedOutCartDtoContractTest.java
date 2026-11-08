package com.taca.cart.api.dto;

import com.taca.cart.api.validation.CheckedOutCartRequestValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CheckedOutCartDtoContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CheckedOutCartRequestValidator validator =
            new CheckedOutCartRequestValidator();

    @Test
    void shouldDeserializeSnakeCaseRequest() {
        String body = """
                {
                  "cart_id": "cart-001",
                  "checkout_group_id": "group-001",
                  "item_ids": ["item-001", "item-002"],
                  "expected_item_revisions": {
                    "item-001": 3,
                    "item-002": 1
                  }
                }
                """;

        CheckedOutCartRequest request =
                JSON.readValue(body, CheckedOutCartRequest.class);

        assertEquals("cart-001", request.cartId());
        assertEquals("group-001", request.checkoutGroupId());
        assertEquals(List.of("item-001", "item-002"), request.itemIds());
        assertEquals(3L, request.expectedItemRevisions().get("item-001"));
        assertEquals(1L, request.expectedItemRevisions().get("item-002"));

        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void shouldSerializeRequestWithDocumentedFieldNames() {
        CheckedOutCartRequest request = new CheckedOutCartRequest(
                "cart-001", "group-001",
                List.of("item-001"),
                Map.of("item-001", 3L));

        JsonNode json = JSON.readTree(JSON.writeValueAsString(request));

        assertEquals("cart-001", json.path("cart_id").asString());
        assertEquals("group-001", json.path("checkout_group_id").asString());
        assertEquals("item-001", json.path("item_ids").get(0).asString());
        assertEquals(3L,
                json.path("expected_item_revisions").path("item-001").asLong());

        assertFalse(json.has("cartId"));
        assertFalse(json.has("checkoutGroupId"));
        assertFalse(json.has("itemIds"));
        assertFalse(json.has("expectedItemRevisions"));
    }

    @Test
    void shouldSerializeResponseInsideApiEnvelope() {
        CheckedOutCartResponse response = new CheckedOutCartResponse(
                "cart-001",
                List.of("item-001"),
                List.of("item-404"),
                List.of("item-002"),
                1);

        ApiResponse<CheckedOutCartResponse> envelope =
                ApiResponse.of(response, "req-001");

        JsonNode json = JSON.readTree(JSON.writeValueAsString(envelope));
        JsonNode data = json.path("data");

        assertEquals("cart-001", data.path("cart_id").asString());
        assertEquals("item-001",
                data.path("removed_item_ids").get(0).asString());
        assertEquals("item-404",
                data.path("missing_item_ids").get(0).asString());
        assertEquals("item-002",
                data.path("skipped_modified_item_ids").get(0).asString());
        assertEquals(1, data.path("remaining_item_count").asInt());
        assertEquals("req-001",
                json.path("meta").path("request_id").asString());

        assertFalse(data.has("removedItemIds"));
        assertFalse(data.has("missingItemIds"));
        assertFalse(data.has("skippedModifiedItemIds"));
        assertFalse(data.has("remainingItemCount"));
    }

    @Test
    void shouldDefensivelyCopyRequestCollections() {
        List<String> itemIds = new ArrayList<>(List.of("item-001"));
        Map<String, Long> revisions = new HashMap<>(
                Map.of("item-001", 3L));

        CheckedOutCartRequest request = new CheckedOutCartRequest(
                "cart-001", "group-001", itemIds, revisions);

        itemIds.add("item-002");
        revisions.put("item-002", 2L);

        assertEquals(List.of("item-001"), request.itemIds());
        assertEquals(Map.of("item-001", 3L),
                request.expectedItemRevisions());

        assertThrows(UnsupportedOperationException.class,
                () -> request.itemIds().add("item-003"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.expectedItemRevisions().put("item-003", 1L));
    }

    @Test
    void shouldKeepResponseCollectionsImmutable() {
        CheckedOutCartResponse response = new CheckedOutCartResponse(
                "cart-001", List.of("item-001"),
                List.of(), List.of(), 0);

        assertThrows(UnsupportedOperationException.class,
                () -> response.removedItemIds().add("item-002"));
    }

    @Test
    void shouldRejectInvalidResponseConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new CheckedOutCartResponse(
                        "cart-001", List.of(), List.of(), List.of(), -1));

        assertThrows(NullPointerException.class,
                () -> new CheckedOutCartResponse(
                        null, List.of(), List.of(), List.of(), 0));
    }
}

package com.taca.cart.application;

import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.integrations.ProductCatalogClient;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class CartIdempotentAddItemService {
    private final CartRequestHasher hasher;
    private final CartIdempotencyLookupService lookupService;
    private final ProductCatalogClient productCatalogClient;
    private final CartIdempotencyTransactionService transactionService;

    public CartIdempotentAddItemService(
            CartRequestHasher hasher,
            CartIdempotencyLookupService lookupService,
            ProductCatalogClient productCatalogClient,
            CartIdempotencyTransactionService transactionService) {
        this.hasher = hasher;
        this.lookupService = lookupService;
        this.productCatalogClient = productCatalogClient;
        this.transactionService = transactionService;
    }

    public CartIdempotencyKey addItem(String buyerUserId, String key,
                                      AddCartItemRequest request, String requestId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(requestId, "requestId");

        String hash = hasher.hash(request);
        var replay = lookupService.findReplay(buyerUserId, key, hash);
        if (replay.isPresent()) return replay.orElseThrow();

        ValidatedSkuSnapshot sku = productCatalogClient.validateSku(
                request.productId(), request.skuId());

        return transactionService.execute(
                buyerUserId, key, request, sku, requestId);
    }
}
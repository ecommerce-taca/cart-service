package com.taca.cart.application;

import com.mongodb.MongoException;
import com.taca.cart.api.dto.AddCartItemResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.integrations.ProductCatalogClient;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class CartAddItemService {
    private final ProductCatalogClient productCatalogClient;
    private final CartMutationService mutationService;

    public CartAddItemService(ProductCatalogClient productCatalogClient,
                              CartMutationService mutationService) {
        this.productCatalogClient = productCatalogClient;
        this.mutationService = mutationService;
    }

    public AddCartItemResponse addItem(String buyerUserId, String productId,
                                       String skuId, int quantity) {
        ValidatedSkuSnapshot sku = productCatalogClient.validateSku(productId, skuId);

        Cart cart;
        try {
            cart = mutationService.addItem(buyerUserId, sku, quantity);
        } catch (DataAccessException | MongoException ex) {
            throw new CartException(
                    CartErrorCode.CART_STORAGE_UNAVAILABLE,
                    "Cart storage is unavailable");
        }

        CartItem item = cart.items().stream()
                .filter(candidate -> candidate.skuId().equals(sku.skuId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Mutated SKU not found in cart"));

        return new AddCartItemResponse(
                cart.id(), item.itemId(), item.quantity(),
                Math.multiplyExact(item.unitPriceSnapshot(), item.quantity()));
    }
}

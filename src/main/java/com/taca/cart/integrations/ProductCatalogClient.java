package com.taca.cart.integrations;

import com.taca.cart.application.ValidatedSkuSnapshot;

public interface ProductCatalogClient {
    ValidatedSkuSnapshot validateSku(String productId, String skuId);
}

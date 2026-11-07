package com.taca.cart.integrations;

import com.taca.cart.application.ProductHydrationResult;

public interface ProductHydrationClient {
    ProductHydrationResult fetchProduct(String productId);
}

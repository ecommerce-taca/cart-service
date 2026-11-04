package com.taca.cart.application;

import com.taca.cart.api.dto.AddCartItemRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;

@Component
public class CartRequestHasher {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String hash(AddCartItemRequest request) {
        Objects.requireNonNull(request, "request");

        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("product_id", request.productId());
        canonical.put("sku_id", request.skuId());
        canonical.put("quantity", request.quantity());

        String json = JSON.writeValueAsString(canonical);

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}

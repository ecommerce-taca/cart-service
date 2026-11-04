package com.taca.cart.api;

import com.taca.cart.api.dto.ApiResponse;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.application.CartQueryService;
import com.taca.cart.observability.RequestIdFilter;
import com.taca.cart.security.ActorContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {
    private final CartQueryService queryService;
    private final ActorContextResolver actorContextResolver;

    public CartController(CartQueryService queryService,
                          ActorContextResolver actorContextResolver) {
        this.queryService = queryService;
        this.actorContextResolver = actorContextResolver;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(HttpServletRequest request) {
        String buyerUserId = actorContextResolver.requireBuyer().buyerUserId();
        CartResponse cart = queryService.getCart(buyerUserId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(cart, RequestIdFilter.resolve(request)));
    }
}

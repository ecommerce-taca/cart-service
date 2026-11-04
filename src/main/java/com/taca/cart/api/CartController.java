package com.taca.cart.api;

import com.taca.cart.api.dto.AddCartItemRequest;
import com.taca.cart.api.dto.AddCartItemResponse;
import com.taca.cart.api.dto.ApiResponse;
import com.taca.cart.api.dto.CartResponse;
import com.taca.cart.api.dto.UpdateCartItemRequest;
import com.taca.cart.api.dto.UpdateCartItemResponse;
import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import com.taca.cart.api.validation.IdempotencyKeyValidator;
import com.taca.cart.application.CartAddItemService;
import com.taca.cart.application.CartQueryService;
import com.taca.cart.application.CartUpdateItemService;
import com.taca.cart.observability.RequestIdFilter;
import com.taca.cart.security.ActorContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {
    private final CartQueryService queryService;
    private final CartAddItemService addItemService;
    private final CartUpdateItemService updateItemService;
    private final ActorContextResolver actorContextResolver;

    public CartController(CartQueryService queryService,
                          CartAddItemService addItemService,
                          CartUpdateItemService updateItemService,
                          ActorContextResolver actorContextResolver) {
        this.queryService = queryService;
        this.addItemService = addItemService;
        this.updateItemService = updateItemService;
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

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<AddCartItemResponse>> addItem(
            @Valid @RequestBody AddCartItemRequest body,
            HttpServletRequest request) {
        String buyerUserId = actorContextResolver.requireBuyer().buyerUserId();

        if (IdempotencyKeyValidator.read(request).isPresent()) {
            throw new CartException(
                    CartErrorCode.CART_INVALID_INPUT,
                    "Idempotency-Key is not supported yet");
        }

        AddCartItemResponse result = addItemService.addItem(
                buyerUserId, body.productId(), body.skuId(), body.quantity());

        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(result, RequestIdFilter.resolve(request)));
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<UpdateCartItemResponse>> updateItem(
            @PathVariable String itemId,
            @Valid @RequestBody UpdateCartItemRequest body,
            HttpServletRequest request) {
        String buyerUserId = actorContextResolver.requireBuyer().buyerUserId();

        UpdateCartItemResponse result = updateItemService.updateQuantity(
                buyerUserId, itemId, body.quantity());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(result, RequestIdFilter.resolve(request)));
    }
}
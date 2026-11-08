package com.taca.cart.api;

import com.taca.cart.api.dto.ApiResponse;
import com.taca.cart.api.dto.InternalCartResponse;
import com.taca.cart.application.InternalCartQueryService;
import com.taca.cart.observability.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/carts")
public class InternalCartController {
    private final InternalCartQueryService queryService;

    public InternalCartController(InternalCartQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{buyerUserId}")
    public ResponseEntity<ApiResponse<InternalCartResponse>> getCart(
            @PathVariable String buyerUserId,
            @RequestParam(value = "item_ids", required = false) String itemIds,
            HttpServletRequest request) {
        InternalCartResponse result = queryService.getCart(buyerUserId, itemIds);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(result, RequestIdFilter.resolve(request)));
    }
}

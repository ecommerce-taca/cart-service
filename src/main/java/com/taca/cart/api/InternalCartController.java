package com.taca.cart.api;

import com.taca.cart.api.dto.ApiResponse;
import com.taca.cart.api.dto.CheckedOutCartRequest;
import com.taca.cart.api.dto.CheckedOutCartResponse;
import com.taca.cart.api.dto.InternalCartResponse;
import com.taca.cart.application.InternalCartCleanupService;
import com.taca.cart.application.InternalCartQueryService;
import com.taca.cart.observability.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/carts")
public class InternalCartController {
    private final InternalCartQueryService queryService;
    private final InternalCartCleanupService cleanupService;

    public InternalCartController(InternalCartQueryService queryService,
                                  InternalCartCleanupService cleanupService) {
        this.queryService = queryService;
        this.cleanupService = cleanupService;
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

    @PostMapping("/{buyerUserId}/checked-out")
    public ResponseEntity<ApiResponse<CheckedOutCartResponse>> checkedOut(
            @PathVariable String buyerUserId,
            @RequestBody CheckedOutCartRequest body,
            HttpServletRequest request) {
        CheckedOutCartResponse result = cleanupService.cleanup(buyerUserId, body);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(result, RequestIdFilter.resolve(request)));
    }
}

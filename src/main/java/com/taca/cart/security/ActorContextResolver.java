package com.taca.cart.security;

import com.taca.cart.api.error.CartErrorCode;
import com.taca.cart.api.error.CartException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class ActorContextResolver {
    public ActorContext requireBuyer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ActorContext actor)
                || authentication.getAuthorities().stream()
                .noneMatch(a -> CartAuthorities.BUYER.equals(a.getAuthority()))) {
            throw new CartException(CartErrorCode.CART_UNAUTHENTICATED, "Buyer authentication required");
        }

        return actor;
    }
}

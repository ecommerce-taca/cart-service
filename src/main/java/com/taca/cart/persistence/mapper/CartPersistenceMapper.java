
package com.taca.cart.persistence.mapper;

import com.taca.cart.domain.Cart;
import com.taca.cart.domain.CartItem;
import com.taca.cart.domain.CartIdempotencyId;
import com.taca.cart.domain.CartIdempotencyKey;
import com.taca.cart.persistence.document.*;
import org.springframework.stereotype.Component;

@Component
public class CartPersistenceMapper {
    public CartDocument toDocument(Cart cart) {
        return new CartDocument(
                cart.id(),
                cart.buyerUserId(),
                cart.items().stream().map(this::toDocument).toList(),
                cart.version(),
                cart.createdAt(),
                cart.updatedAt()
        );
    }

    public Cart toDomain(CartDocument document) {
        return new Cart(
                document.id(),
                document.buyerUserId(),
                document.items().stream().map(this::toDomain).toList(),
                document.version(),
                document.createdAt(),
                document.updatedAt()
        );
    }

    public CartItemDocument toDocument(CartItem item) {
        return new CartItemDocument(
                item.itemId(),
                item.productId(),
                item.skuId(),
                item.shopId(),
                item.quantity(),
                item.itemRevision(),
                item.titleSnapshot(),
                item.skuLabelSnapshot(),
                item.unitPriceSnapshot(),
                item.available(),
                item.addedAt(),
                item.updatedAt()
        );
    }

    public CartItem toDomain(CartItemDocument document) {
        return new CartItem(
                document.itemId(),
                document.productId(),
                document.skuId(),
                document.shopId(),
                document.quantity(),
                document.itemRevision(),
                document.titleSnapshot(),
                document.skuLabelSnapshot(),
                document.unitPriceSnapshot(),
                document.available(),
                document.addedAt(),
                document.updatedAt()
        );
    }

    public CartIdempotencyKeyDocument toDocument(CartIdempotencyKey key) {
        var id = new CartIdempotencyIdDocument(
                key.id().buyerUserId(),
                key.id().key()
        );

        return new CartIdempotencyKeyDocument(
                id,
                key.requestHash(),
                key.response(),
                key.responseStatus(),
                key.expiresAt(),
                key.createdAt()
        );
    }

    public CartIdempotencyKey toDomain(CartIdempotencyKeyDocument document) {
        var id = new CartIdempotencyId(
                document.id().buyerUserId(),
                document.id().key()
        );

        return new CartIdempotencyKey(
                id,
                document.requestHash(),
                document.response(),
                document.responseStatus(),
                document.expiresAt(),
                document.createdAt()
        );
    }
}

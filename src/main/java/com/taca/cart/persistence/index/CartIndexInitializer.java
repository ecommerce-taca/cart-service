
package com.taca.cart.persistence.index;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "cart.indexes", name = "initialize", havingValue = "true")
public class CartIndexInitializer implements ApplicationRunner {
    private static final String CARTS = "carts";
    private static final String KEYS = "cart_idempotency_keys";

    private final MongoOperations mongoOperations;

    public CartIndexInitializer(MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndexes();
    }

    public void ensureIndexes() {
        mongoOperations.indexOps(CARTS).createIndex(
                new Index().on("buyer_user_id", Sort.Direction.ASC)
                        .unique().named("ux_carts_buyer_user_id")
        );

        mongoOperations.indexOps(CARTS).createIndex(
                new Index().on("updated_at", Sort.Direction.ASC)
                        .named("idx_carts_updated_at")
        );

        mongoOperations.indexOps(KEYS).createIndex(
                new Index().on("expires_at", Sort.Direction.ASC)
                        .expire(0).named("ttl_cart_idempotency_keys_expires_at")
        );
    }

}

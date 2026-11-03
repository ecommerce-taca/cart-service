package com.taca.cart.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationPropertiesTest {
    @Test
    void shouldBindCartDefaultsFromConfigurationKeys() {
        var source = new MapConfigurationPropertySource(Map.of(
                "cart.max-items", "100",
                "cart.item-qty-min", "1",
                "cart.item-qty-max", "999",
                "cart.hydrate-timeout", "3s",
                "cart.idempotency-ttl", "24h",
                "cart.write-retry-max", "3"
        ));

        var properties = new Binder(source)
                .bind("cart", Bindable.of(CartProperties.class))
                .get();

        assertEquals(100, properties.maxItems());
        assertEquals(1, properties.itemQtyMin());
        assertEquals(999, properties.itemQtyMax());
        assertEquals(Duration.ofSeconds(3), properties.hydrateTimeout());
        assertEquals(Duration.ofHours(24), properties.idempotencyTtl());
        assertEquals(3, properties.writeRetryMax());
    }

    @Test
    void shouldBindProductDefaultsFromConfigurationKeys() {
        var source = new MapConfigurationPropertySource(Map.of(
                "product.timeout", "2s",
                "product.cache-ttl", "30s",
                "product.max-concurrency", "10"
        ));

        var properties = new Binder(source)
                .bind("product", Bindable.of(ProductProperties.class))
                .get();

        assertEquals(Duration.ofSeconds(2), properties.timeout());
        assertEquals(Duration.ofSeconds(30), properties.cacheTtl());
        assertEquals(10, properties.maxConcurrency());
    }
}
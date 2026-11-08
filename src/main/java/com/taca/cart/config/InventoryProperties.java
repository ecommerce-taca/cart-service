package com.taca.cart.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(
        @NotNull Duration timeout,
        @Min(1) @Max(100) int maxBatchSize,
        @Min(1) int maxConcurrency
) {
    public InventoryProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Inventory timeout must be positive");
        }
    }
}

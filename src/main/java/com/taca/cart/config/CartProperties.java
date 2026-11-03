package com.taca.cart.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "cart")
public record CartProperties(
        @Min(1) int maxItems,
        @Min(1) int itemQtyMin,
        @Min(1) int itemQtyMax,
        @NotNull Duration hydrateTimeout,
        @NotNull Duration idempotencyTtl,
        @Min(0) int writeRetryMax
) {}
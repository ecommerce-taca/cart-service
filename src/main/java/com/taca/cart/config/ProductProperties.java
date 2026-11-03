package com.taca.cart.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "product")
public record ProductProperties(
        @NotNull Duration timeout,
        @NotNull Duration cacheTtl,
        @Min(1) int maxConcurrency
) {}
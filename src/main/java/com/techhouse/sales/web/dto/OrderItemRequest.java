package com.techhouse.sales.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** @param unitPrice per unit, in the order's currency */
public record OrderItemRequest(
        @NotNull UUID productId,
        @Min(1) @Max(100_000) int quantity,
        @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal unitPrice
) {}

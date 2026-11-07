package com.techhouse.procurement.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceItemRequest(
        @NotNull UUID productId,
        @Min(1) @Max(1_000_000) int quantity,
        /** Supplier price per unit (e.g. the price in China), before shipping. */
        @NotNull @DecimalMin("0.0001") @DecimalMax("100000000") BigDecimal unitPriceUsd
) {}

package com.techhouse.finance.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** SYP per 1 USD, as the exchange offices quote it. */
public record ManualRateRequest(
        @NotNull @DecimalMin("0.0001") @DecimalMax("100000000") BigDecimal buyRate,
        @NotNull @DecimalMin("0.0001") @DecimalMax("100000000") BigDecimal sellRate
) {}

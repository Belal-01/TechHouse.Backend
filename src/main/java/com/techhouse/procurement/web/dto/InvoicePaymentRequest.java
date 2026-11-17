package com.techhouse.procurement.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/** Part of the goods payment (USD): from a box's USD, or an owner's / investor's own money. */
public record InvoicePaymentRequest(
        @NotNull @Pattern(regexp = "ACCOUNT|OWNER|INVESTOR") String source,
        UUID accountId,
        UUID partnerId,
        @NotNull @DecimalMin("0.0001") @DecimalMax("100000000000") BigDecimal amountUsd,
        Boolean useInvestorBalance
) {}

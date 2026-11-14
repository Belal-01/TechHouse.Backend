package com.techhouse.procurement.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A cost on top of the goods, and who paid it. {@code source} ACCOUNT pays from a box ({@code accountId}, or the default
 * box) in the cost's currency; OWNER / INVESTOR is that person's own money ({@code partnerId}).
 */
public record InvoiceCostRequest(
        @NotNull @Pattern(regexp = "CHINA_SHIPPING|SEA_FREIGHT|CUSTOMS|OTHER") String kind,
        @Size(max = 255) String description,
        @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal amount,
        @NotNull @Pattern(regexp = "USD|SYP") String currency,
        @NotNull @Pattern(regexp = "ACCOUNT|OWNER|INVESTOR") String source,
        UUID accountId,
        UUID partnerId
) {}

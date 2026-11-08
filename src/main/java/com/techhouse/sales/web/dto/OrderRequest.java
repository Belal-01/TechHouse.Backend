package com.techhouse.sales.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * @param currency           what the customer pays in: USD or SYP
 * @param deliveryFeeCharged delivery the customer pays on top of the items (order currency), optional
 * @param deliveryCost       what the shop pays the delivery company, recorded as an expense now
 * @param deliveryCostCurrency SYP by default
 */
public record OrderRequest(
        @NotBlank @Size(max = 255) String customerName,
        @Size(max = 50) String customerPhone,
        @Size(max = 2000) String customerAddress,
        @NotNull @Pattern(regexp = "USD|SYP") String currency,
        @NotEmpty @Size(max = 100) List<@Valid OrderItemRequest> items,
        @DecimalMin("0") @DecimalMax("10000000000") BigDecimal deliveryFeeCharged,
        @DecimalMin("0") @DecimalMax("10000000000") BigDecimal deliveryCost,
        @Pattern(regexp = "USD|SYP") String deliveryCostCurrency,
        UUID deliveryCompanyId,
        @Size(max = 5000) String notes
) {}

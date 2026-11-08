package com.techhouse.procurement.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseInvoiceRequest(
        UUID supplierId,
        @NotNull LocalDate invoiceDate,
        /** Shipping for the whole invoice; spread over the items by value. */
        @DecimalMin("0") @DecimalMax("100000000") BigDecimal shippingCostUsd,
        @DecimalMin("0") @DecimalMax("100000000") BigDecimal customsCostUsd,
        /** INCOME (paid from the shop's cash) or EXTERNAL_CAPITAL (the owner put in new money for it). */
        @NotNull @Pattern(regexp = "INCOME|EXTERNAL_CAPITAL") String paidFrom,
        /** The box that pays (its USD balance); the default box when omitted. */
        UUID accountId,
        @Size(max = 5000) String notes,
        @NotEmpty @Size(max = 200) List<@Valid InvoiceItemRequest> items
) {}

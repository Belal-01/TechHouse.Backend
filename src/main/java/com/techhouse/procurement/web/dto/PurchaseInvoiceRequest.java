package com.techhouse.procurement.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseInvoiceRequest(
        UUID supplierId,
        @NotNull LocalDate invoiceDate,
        /** IN_TRANSIT: bought and on its way, not sellable yet. RECEIVED: already here, goes into stock now. */
        @NotNull @Pattern(regexp = "IN_TRANSIT|RECEIVED") String status,
        @Size(max = 5000) String notes,
        @NotEmpty @Size(max = 200) List<@Valid InvoiceItemRequest> items,
        /** Costs known now (e.g. shipping inside China); more can be added later. */
        @Size(max = 50) List<@Valid InvoiceCostRequest> costs,
        /** Who paid for the goods; must add up to the goods total. */
        @NotEmpty @Size(max = 20) List<@Valid InvoicePaymentRequest> payments
) {}

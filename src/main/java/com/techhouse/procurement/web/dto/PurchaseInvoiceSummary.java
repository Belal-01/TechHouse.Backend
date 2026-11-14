package com.techhouse.procurement.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseInvoiceSummary(
        UUID id,
        String invoiceNumber,
        PurchaseInvoiceResponse.Ref supplier,
        LocalDate invoiceDate,
        String status,
        int itemCount,
        BigDecimal totalUsd,
        boolean hasAttachment,
        boolean voided,
        /** Part of it was paid with outside money not yet attributed to a person. */
        boolean unassigned
) {}

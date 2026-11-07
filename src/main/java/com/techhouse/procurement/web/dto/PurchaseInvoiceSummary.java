package com.techhouse.procurement.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseInvoiceSummary(
        UUID id,
        String invoiceNumber,
        PurchaseInvoiceResponse.SupplierRef supplier,
        LocalDate invoiceDate,
        int itemCount,
        BigDecimal totalUsd,
        String paidFrom,
        boolean hasAttachment
) {}

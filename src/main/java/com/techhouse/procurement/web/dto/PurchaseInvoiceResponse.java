package com.techhouse.procurement.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseInvoiceResponse(
        UUID id,
        String invoiceNumber,
        SupplierRef supplier,
        LocalDate invoiceDate,
        BigDecimal shippingCostUsd,
        BigDecimal customsCostUsd,
        BigDecimal itemsTotalUsd,
        /** Items + shipping + customs: what the invoice cost in total. */
        BigDecimal totalUsd,
        String paidFrom,
        SupplierRef account,
        String notes,
        boolean hasAttachment,
        List<Item> items,
        int version,
        Instant createdAt
) {
    public record SupplierRef(UUID id, String name) {}

    public record ProductRef(UUID id, String name, String sku) {}

    public record Item(
            UUID id,
            ProductRef product,
            int quantity,
            BigDecimal unitPriceUsd,
            BigDecimal extraCostUsd,
            /** Real cost of one unit after its share of shipping/customs. */
            BigDecimal landedUnitCost,
            BigDecimal lineTotalUsd
    ) {}
}

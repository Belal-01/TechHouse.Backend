package com.techhouse.procurement.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseInvoiceResponse(
        UUID id,
        String invoiceNumber,
        Ref supplier,
        LocalDate invoiceDate,
        /** IN_TRANSIT or RECEIVED. */
        String status,
        Instant receivedAt,
        BigDecimal itemsTotalUsd,
        BigDecimal costsTotalUsd,
        /** Goods + all costs: what the invoice cost in total. */
        BigDecimal totalUsd,
        Funding funding,
        String notes,
        boolean hasAttachment,
        List<Item> items,
        List<Cost> costs,
        List<Payment> payments,
        /** Units of this invoice still in stock (0 while on the way). */
        int unitsInStock,
        int version,
        Instant createdAt,
        Instant voidedAt,
        String voidNote,
        /** True when it can still be voided: not voided, and none of its units sold or taken by an order. */
        boolean voidable
) {
    public record Ref(UUID id, String name) {}
    public record ProductRef(UUID id, String name, String sku) {}

    public record Item(
            UUID id,
            ProductRef product,
            int quantity,
            BigDecimal unitPriceUsd,
            /** This line's share of the costs. */
            BigDecimal extraCostUsd,
            /** Real cost of one unit: price + its share of the costs (an estimate while on the way). */
            BigDecimal landedUnitCost,
            BigDecimal lineTotalUsd
    ) {}

    public record Cost(
            UUID id,
            String kind,
            String description,
            BigDecimal amount,
            String currency,
            BigDecimal exchangeRate,
            BigDecimal amountUsd,
            boolean afterReceipt,
            Instant createdAt,
            /** Can be removed: only while the goods are on the way. */
            boolean removable
    ) {}

    /** @param costId null when it pays for the goods */
    public record Payment(UUID id, UUID costId, String source, Ref account, Ref partner, BigDecimal amount, String currency,
                          BigDecimal amountUsd) {}

    /** USD paid by each kind of source; unassigned = outside money not yet attributed to a person. */
    public record Funding(BigDecimal storeUsd, BigDecimal ownersUsd, BigDecimal investorsUsd, BigDecimal unassignedUsd) {}
}

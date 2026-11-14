package com.techhouse.procurement.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_items")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    /** Order on the invoice, from 0. */
    @Column(nullable = false, updatable = false)
    private int position;

    /** Supplier price per unit, before shipping. */
    @Column(name = "unit_price_usd", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceUsd;

    /** This line's share of the invoice costs (whole line, not per unit). Grows when a cost is added. */
    @Column(name = "extra_cost_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal extraCostUsd;

    /**
     * What one unit really costs: supplier price + its share of the costs. While on the way it is an estimate from
     * the costs so far; after a later cost it is the cost of the units still in stock.
     */
    @Column(name = "landed_unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal landedUnitCost;
}

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

    /** Supplier price per unit, before shipping. */
    @Column(name = "unit_price_usd", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceUsd;

    /** This line's allocated share of the invoice shipping + customs (whole line, not per unit). */
    @Column(name = "extra_cost_usd", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal extraCostUsd;

    /** unitPrice + extraCost / quantity: what one unit really cost once it arrived. */
    @Column(name = "landed_unit_cost", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal landedUnitCost;
}

package com.techhouse.procurement.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One cost of a purchase invoice on top of the goods (China shipping, sea freight, customs...). */
@Entity
@Table(name = "purchase_invoice_costs")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceCost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    /** CHINA_SHIPPING, SEA_FREIGHT, CUSTOMS or OTHER. */
    @Column(nullable = false, updatable = false, length = 30)
    private String kind;

    @Column(updatable = false)
    private String description;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "exchange_rate_id", updatable = false)
    private UUID exchangeRateId;

    @Column(name = "exchange_rate_snapshot", updatable = false, precision = 19, scale = 4)
    private BigDecimal exchangeRateSnapshot;

    @Column(name = "amount_usd", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amountUsd;

    /** Added after the goods arrived: spread over the units still in stock then. */
    @Column(name = "after_receipt", nullable = false, updatable = false)
    private boolean afterReceipt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

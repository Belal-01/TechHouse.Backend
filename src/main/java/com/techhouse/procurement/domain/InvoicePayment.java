package com.techhouse.procurement.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Who paid part of an invoice: the goods (no cost) or one cost, from a box, an owner, an investor. */
@Entity
@Table(name = "purchase_invoice_payments")
@Getter
@Setter
@NoArgsConstructor
public class InvoicePayment {

    public static final String ACCOUNT = "ACCOUNT";
    public static final String OWNER = "OWNER";
    public static final String INVESTOR = "INVESTOR";
    /** Outside money from before payment sources existed, not yet assigned to a person. */
    public static final String EXTERNAL = "EXTERNAL";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    /** Null: pays for the goods. */
    @Column(name = "cost_id", updatable = false)
    private UUID costId;

    @Column(nullable = false, updatable = false, length = 20)
    private String source;

    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(name = "partner_id", updatable = false)
    private UUID partnerId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "amount_usd", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amountUsd;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

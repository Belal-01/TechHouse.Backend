package com.techhouse.finance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A row of the append-only ledger (the database rejects UPDATE and DELETE). Amounts are signed. */
@Entity
@Table(name = "cash_movements")
@Getter
@Setter
@NoArgsConstructor
public class CashMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "wallet_currency", nullable = false, updatable = false, length = 3)
    private String walletCurrency;

    @Column(name = "movement_type", nullable = false, updatable = false, length = 30)
    private String movementType;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "exchange_rate_id", updatable = false)
    private UUID exchangeRateId;

    @Column(name = "exchange_rate_snapshot", updatable = false, precision = 19, scale = 4)
    private BigDecimal exchangeRateSnapshot;

    @Column(name = "reference_type", updatable = false, length = 30)
    private String referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(updatable = false)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

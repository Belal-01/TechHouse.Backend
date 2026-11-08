package com.techhouse.finance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An expense in its own currency, with the USD value at the rate of the day. Voided, never deleted. */
@Entity
@Table(name = "expenses")
@Getter
@Setter
@NoArgsConstructor
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /** The box that paid it; null when it was paid out of a staff advance. */
    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "exchange_rate_id")
    private UUID exchangeRateId;

    @Column(name = "exchange_rate_snapshot", precision = 19, scale = 4)
    private BigDecimal exchangeRateSnapshot;

    @Column(name = "amount_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountUsd;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

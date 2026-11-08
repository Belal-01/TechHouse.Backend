package com.techhouse.finance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One change to what a person owes the shop. Append-only (the database rejects UPDATE/DELETE).
 * WITHDRAWAL (+): took money from a box. RETURN (−): gave it back to a box. EXPENSE (−): spent it on the shop.
 */
@Entity
@Table(name = "advance_entries")
@Getter
@Setter
@NoArgsConstructor
public class AdvanceEntry {

    public enum Kind { WITHDRAWAL, RETURN, EXPENSE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Kind kind;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(name = "expense_id", updatable = false)
    private UUID expenseId;

    @Column(updatable = false)
    private String note;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

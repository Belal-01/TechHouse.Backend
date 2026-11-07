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

/**
 * One rate per store, day and pair (e.g. USD/SYP = how many SYP one dollar costs). {@code midRate} = (buy + sell) / 2
 * is the rate the app uses. Orders and expenses copy the value they used, so later changes never rewrite them.
 */
@Entity
@Table(name = "exchange_rates")
@Getter
@Setter
@NoArgsConstructor
public class ExchangeRate {

    public static final String USD_SYP = "USD/SYP";

    public enum Source { API, MANUAL }

    /** PENDING_REVIEW: fetched, but too far from the last approved rate to use without a look. */
    public enum Status { ACTIVE, PENDING_REVIEW }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "rate_date", nullable = false, updatable = false)
    private LocalDate rateDate;

    @Column(name = "currency_pair", nullable = false, updatable = false, length = 7)
    private String currencyPair;

    @Column(name = "buy_rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal buyRate;

    @Column(name = "sell_rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal sellRate;

    @Column(name = "mid_rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal midRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Status status;

    /** When the provider last changed this rate (null for manual rates). */
    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

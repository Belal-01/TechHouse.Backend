package com.techhouse.finance.domain;

import com.techhouse.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** A company owner, or an investor who funds specific invoices. */
@Entity
@Table(name = "partners")
@Getter
@Setter
@NoArgsConstructor
public class Partner extends BaseEntity {

    public static final String OWNER = "OWNER";
    public static final String INVESTOR = "INVESTOR";

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false)
    private String name;

    /** OWNER or INVESTOR; fixed once created. */
    @Column(nullable = false, updatable = false, length = 20)
    private String kind;

    /** Owner: their share of the company. Investor: default share of the profit on what they fund. */
    @Column(name = "profit_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal profitPercent;

    @Column(length = 50)
    private String phone;

    private String notes;

    @Column(nullable = false)
    private boolean active = true;
}

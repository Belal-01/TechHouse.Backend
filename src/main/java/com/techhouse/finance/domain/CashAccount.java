package com.techhouse.finance.domain;

import com.techhouse.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** A place money is kept ("in hand", "Sham Cash"). Holds a USD and a SYP balance, both computed from the ledger. */
@Entity
@Table(name = "cash_accounts")
@Getter
@Setter
@NoArgsConstructor
public class CashAccount extends BaseEntity {

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 100)
    private String name;

    /** Used when a movement doesn't name its account. Exactly one per store. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultAccount;

    @Column(nullable = false)
    private boolean active = true;
}

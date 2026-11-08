package com.techhouse.finance.domain;

import com.techhouse.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "expense_categories")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseCategory extends BaseEntity {

    /** The category the app books order delivery fees under. */
    public static final String DELIVERY = "DELIVERY";

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 100)
    private String name;

    /** Set on categories the app itself relies on; those can be renamed but not removed or deactivated. */
    @Column(name = "system_key", updatable = false, length = 30)
    private String systemKey;

    @Column(nullable = false)
    private boolean active = true;
}

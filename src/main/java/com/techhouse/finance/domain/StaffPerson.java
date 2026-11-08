package com.techhouse.finance.domain;

import com.techhouse.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Someone (e.g. a store manager) who may take money from the shop as an advance. Not a login. */
@Entity
@Table(name = "staff_people")
@Getter
@Setter
@NoArgsConstructor
public class StaffPerson extends BaseEntity {

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false)
    private String name;

    @Column(length = 50)
    private String phone;

    @Column(nullable = false)
    private boolean active = true;
}

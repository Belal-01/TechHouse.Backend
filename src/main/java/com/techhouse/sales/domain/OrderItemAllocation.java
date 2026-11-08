package com.techhouse.sales.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Which lot an order line took its units from, at that lot's landed cost (USD). */
@Entity
@Table(name = "order_item_allocations")
@Getter
@Setter
@NoArgsConstructor
public class OrderItemAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private UUID orderItemId;

    @Column(name = "lot_id", nullable = false, updatable = false)
    private UUID lotId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal unitCost;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

package com.techhouse.inventory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One FIFO layer of stock. {@code unitCost} is the landed cost (supplier price + allocated shipping). */
@Entity
@Table(name = "inventory_lots")
@Getter
@Setter
@NoArgsConstructor
public class InventoryLot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "invoice_item_id", nullable = false, updatable = false)
    private UUID invoiceItemId;

    @Column(name = "qty_in", nullable = false, updatable = false)
    private int qtyIn;

    @Column(name = "qty_left", nullable = false)
    private int qtyLeft;

    @Column(name = "unit_cost", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

package com.techhouse.sales.domain;

import com.techhouse.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A sale. Amounts are in the order's currency; {@code exchangeRateSnapshot} is the sell rate (SYP per USD) fixed when
 * the order was entered, and every USD figure of this order is computed with it.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order extends BaseEntity {

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "order_number", nullable = false, updatable = false, length = 30)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(nullable = false, length = 20)
    private String channel = "MANUAL";

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "exchange_rate_id", updatable = false)
    private UUID exchangeRateId;

    @Column(name = "exchange_rate_snapshot", updatable = false, precision = 19, scale = 4)
    private BigDecimal exchangeRateSnapshot;

    @Column(name = "items_total", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal itemsTotal;

    /** Delivery charged to the customer on top of the items (order currency). */
    @Column(name = "delivery_fee_charged", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal deliveryFeeCharged = BigDecimal.ZERO;

    /** What the shop pays the delivery company (recorded as an expense). */
    @Column(name = "delivery_cost", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal deliveryCost = BigDecimal.ZERO;

    @Column(name = "delivery_cost_currency", updatable = false, length = 3)
    private String deliveryCostCurrency;

    @Column(name = "delivery_company_id", updatable = false)
    private UUID deliveryCompanyId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone", length = 50)
    private String customerPhone;

    @Column(name = "customer_address")
    private String customerAddress;

    @Column(name = "return_type", length = 20)
    private String returnType;

    private String notes;

    @Column(name = "ordered_at", nullable = false, updatable = false)
    private Instant orderedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    /** What the customer pays on delivery: items + delivery charged, in the order currency. */
    public BigDecimal customerTotal() {
        return itemsTotal.add(deliveryFeeCharged);
    }
}

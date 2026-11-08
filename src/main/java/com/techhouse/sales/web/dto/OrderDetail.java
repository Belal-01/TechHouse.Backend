package com.techhouse.sales.web.dto;

import com.techhouse.sales.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderDetail(
        UUID id,
        String orderNumber,
        OrderStatus status,
        Customer customer,
        String currency,
        /** Sell rate (SYP per USD) fixed when the order was entered; null if no rate was needed or available. */
        BigDecimal exchangeRate,
        List<Item> items,
        BigDecimal itemsTotal,
        BigDecimal deliveryFeeCharged,
        BigDecimal customerTotal,
        BigDecimal deliveryCost,
        String deliveryCostCurrency,
        Ref deliveryCompany,
        /** The box the money went into (delivered orders). */
        Ref paymentAccount,
        OrderMoney money,
        String notes,
        Instant orderedAt,
        Instant deliveredAt,
        Instant returnedAt,
        Instant voidedAt,
        List<History> history,
        int version
) {
    public record Ref(UUID id, String name) {}

    public record Customer(String name, String phone, String address) {}

    /** @param costUsd landed cost of the units taken (FIFO) */
    public record Item(UUID id, ProductRef product, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                       BigDecimal costUsd) {}

    public record ProductRef(UUID id, String name, String sku) {}

    public record History(OrderStatus fromStatus, OrderStatus toStatus, String note, Instant at) {}
}

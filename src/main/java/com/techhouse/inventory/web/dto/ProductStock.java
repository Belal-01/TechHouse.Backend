package com.techhouse.inventory.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One product's stock: totals, the lots it came in (oldest first, the FIFO order) and the latest orders that took from it. */
public record ProductStock(StockItem summary, int lowStockThreshold, List<Lot> lots, List<Outgoing> recentOrders) {

    /**
     * @param unitCost landed cost per unit (supplier price + its share of shipping)
     * @param qtyLeft  still in the warehouse; sold or on the way = qtyIn − qtyLeft
     */
    public record Lot(UUID id, UUID invoiceId, String invoiceNumber, LocalDate invoiceDate, String supplierName,
                      int qtyIn, int qtyLeft, BigDecimal unitCost, Instant receivedAt) {}

    /** @param unitCost average FIFO cost of the units this order took */
    public record Outgoing(UUID orderId, String orderNumber, String status, int quantity, BigDecimal unitCost,
                           Instant orderedAt) {}
}

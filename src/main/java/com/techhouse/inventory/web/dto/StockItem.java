package com.techhouse.inventory.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param available      units in the warehouse, ready to sell
 * @param valueUsd       their landed cost
 * @param inTransit      units out with customers on pending orders
 * @param inTransitValueUsd their landed cost
 */
public record StockItem(
        UUID productId,
        String name,
        String sku,
        BigDecimal sellingPrice,
        boolean active,
        int available,
        BigDecimal valueUsd,
        int inTransit,
        BigDecimal inTransitValueUsd
) {}

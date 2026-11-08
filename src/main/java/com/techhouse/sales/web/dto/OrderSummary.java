package com.techhouse.sales.web.dto;

import com.techhouse.sales.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderSummary(
        UUID id,
        String orderNumber,
        OrderStatus status,
        String customerName,
        String customerPhone,
        String currency,
        BigDecimal customerTotal,
        int itemCount,
        OrderDetail.Ref deliveryCompany,
        OrderMoney money,
        Instant orderedAt
) {}

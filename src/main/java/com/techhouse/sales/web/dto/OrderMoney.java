package com.techhouse.sales.web.dto;

import java.math.BigDecimal;

/**
 * The order in USD at its fixed rate. For a pending order the profit is what it will make if delivered;
 * a returned order loses its delivery cost; a voided order counts for nothing.
 */
public record OrderMoney(BigDecimal revenueUsd, BigDecimal cogsUsd, BigDecimal deliveryCostUsd, BigDecimal profitUsd) {}

package com.techhouse.reporting.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** All amounts in USD unless the field says otherwise. SYP is valued at the current sell rate. */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record Dashboard(Period period, Pnl pnl, ProfitShare profitShare, Wealth.Totals wealth, Pending pending, List<Point> series,
                            String seriesUnit, List<TopProduct> topProducts, Returns returns, Alerts alerts, Rate rate) {}

    public record Period(LocalDate from, LocalDate to) {}

    /** Spec §4: revenue (delivered in the period) − FIFO cost − expenses (dated in the period, not voided). */
    public record Pnl(BigDecimal revenueUsd, BigDecimal cogsUsd, BigDecimal grossProfitUsd, BigDecimal expensesUsd,
                      BigDecimal netProfitUsd, int deliveredOrders) {}

    /** {@code percent} of the period's net profit (0 when there is no profit), under a name the owner chooses. */
    public record ProfitShare(String label, BigDecimal percent, BigDecimal amountUsd) {}

    public record ProfitShareRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 60) String label,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0")
            @jakarta.validation.constraints.DecimalMax("100") BigDecimal percent
    ) {}

    /** Orders still on the way right now; expected profit = revenue − cost − delivery. */
    public record Pending(int count, BigDecimal revenueUsd, BigDecimal expectedProfitUsd) {}

    /** One bucket of the chart: a day, or a month for long periods. */
    public record Point(LocalDate date, BigDecimal revenueUsd, BigDecimal netProfitUsd) {}

    public record TopProduct(UUID productId, String name, int quantity, BigDecimal revenueUsd, BigDecimal profitUsd) {}

    /** Orders returned in the period, and what their delivery fees cost. */
    public record Returns(int returned, int delivered, BigDecimal deliveryCostUsd) {}

    public record Alerts(int lowStockThreshold, List<LowStock> lowStock, int lateOrderDays, List<LateOrder> lateOrders) {}

    public record LowStock(UUID productId, String name, int available) {}

    public record LateOrder(UUID orderId, String orderNumber, String customerName, Instant orderedAt, long days) {}

    /** The rate SYP is valued at; null sellRate = no rate yet (SYP left out of USD totals). */
    public record Rate(BigDecimal sellRate, LocalDate rateDate, boolean stale, boolean pendingReview) {}

    /** Everything the shop owns, in USD, and where it sits. */
    public record Wealth(Totals totals, List<Account> accounts, List<Stock> stock, List<Advance> advances, Rate rate) {

        /** @param sypExcluded true when there is no rate, so SYP amounts could not be counted */
        public record Totals(BigDecimal cashUsd, BigDecimal inventoryUsd, BigDecimal inTransitUsd, BigDecimal advancesUsd,
                             BigDecimal totalUsd, boolean sypExcluded) {}

        public record Account(UUID id, String name, BigDecimal usd, BigDecimal syp, BigDecimal valueUsd) {}

        public record Stock(UUID productId, String name, int available, BigDecimal valueUsd, int inTransit,
                            BigDecimal inTransitValueUsd) {}

        public record Advance(UUID personId, String name, BigDecimal usd, BigDecimal syp, BigDecimal valueUsd) {}
    }
}

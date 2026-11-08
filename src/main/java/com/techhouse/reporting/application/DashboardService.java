package com.techhouse.reporting.application;

import com.techhouse.finance.application.ExchangeRateService;
import com.techhouse.finance.web.dto.CurrentRateResponse;
import com.techhouse.reporting.web.DashboardDtos.Alerts;
import com.techhouse.reporting.web.DashboardDtos.Dashboard;
import com.techhouse.reporting.web.DashboardDtos.LateOrder;
import com.techhouse.reporting.web.DashboardDtos.LowStock;
import com.techhouse.reporting.web.DashboardDtos.Pending;
import com.techhouse.reporting.web.DashboardDtos.Period;
import com.techhouse.reporting.web.DashboardDtos.Pnl;
import com.techhouse.reporting.web.DashboardDtos.ProfitShare;
import com.techhouse.reporting.web.DashboardDtos.ProfitShareRequest;
import com.techhouse.reporting.web.DashboardDtos.Point;
import com.techhouse.reporting.web.DashboardDtos.Rate;
import com.techhouse.reporting.web.DashboardDtos.Returns;
import com.techhouse.reporting.web.DashboardDtos.TopProduct;
import com.techhouse.reporting.web.DashboardDtos.Wealth;
import com.techhouse.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Read-only figures for the dashboard and the wealth page, straight from the books. Periods are whole days in the
 * store's time zone. Revenue counts when an order is delivered (at its fixed rate), expenses by their date.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Revenue of an order in USD at its fixed rate (spec §4). */
    private static final String REVENUE_USD = """
            case when o.currency = 'USD' then o.items_total + o.delivery_fee_charged
                 else (o.items_total + o.delivery_fee_charged) / o.exchange_rate_snapshot end""";

    private final JdbcTemplate jdbc;
    private final ExchangeRateService rates;

    @Value("${app.dashboard.low-stock-threshold:3}")
    private int lowStockThreshold;

    @Value("${app.dashboard.late-order-days:4}")
    private int lateOrderDays;

    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID storeId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "Choose a valid period");
        }
        if (ChronoUnit.DAYS.between(from, to) > 3700) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "The period is too long");
        }
        ZoneId zone = zone(storeId);
        Timestamp start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        Rate rate = rate(storeId);

        BigDecimal revenue = sum("select coalesce(sum(" + REVENUE_USD + "), 0) from orders o "
                + "where o.store_id = ? and o.status = 'DELIVERED' and o.delivered_at >= ? and o.delivered_at < ?", storeId, start, end);
        BigDecimal cogs = sum("""
                select coalesce(sum(a.quantity * a.unit_cost), 0) from order_item_allocations a
                join order_items i on i.id = a.order_item_id join orders o on o.id = i.order_id
                where o.store_id = ? and o.status = 'DELIVERED' and o.delivered_at >= ? and o.delivered_at < ?""", storeId, start, end);
        BigDecimal expenses = sum("""
                select coalesce(sum(amount_usd), 0) from expenses
                where store_id = ? and voided_at is null and expense_date between ? and ?""", storeId, from, to);
        int delivered = count("select count(*) from orders where store_id = ? and status = 'DELIVERED' and delivered_at >= ? and delivered_at < ?",
                storeId, start, end);
        BigDecimal gross = revenue.subtract(cogs);
        Pnl pnl = new Pnl(money(revenue), money(cogs), money(gross), money(expenses), money(gross.subtract(expenses)), delivered);

        return new Dashboard(new Period(from, to), pnl, profitShare(storeId, pnl.netProfitUsd()), wealth(storeId).totals(), pending(storeId),
                series(storeId, zone, from, to), ChronoUnit.DAYS.between(from, to) > 62 ? "MONTH" : "DAY",
                topProducts(storeId, start, end), returns(storeId, start, end, delivered), alerts(storeId), rate);
    }

    @Transactional(readOnly = true)
    public Wealth wealth(UUID storeId) {
        Rate rate = rate(storeId);
        BigDecimal sell = rate.sellRate();

        List<Wealth.Account> accounts = jdbc.query("""
                select a.id, a.name,
                       coalesce(sum(m.amount) filter (where m.wallet_currency = 'USD'), 0),
                       coalesce(sum(m.amount) filter (where m.wallet_currency = 'SYP'), 0)
                from cash_accounts a left join cash_movements m on m.account_id = a.id
                where a.store_id = ? group by a.id, a.name, a.is_default order by a.is_default desc, a.name""",
                (rs, i) -> new Wealth.Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), money(usd(rs.getBigDecimal(3), rs.getBigDecimal(4), sell))), storeId);

        List<Wealth.Stock> stock = jdbc.query("""
                select id, name, available, value, in_transit, in_transit_value from (
                    select p.id, p.name,
                           coalesce((select sum(l.qty_left) from inventory_lots l where l.product_id = p.id), 0) as available,
                           coalesce((select sum(l.qty_left * l.unit_cost) from inventory_lots l where l.product_id = p.id), 0) as value,
                           coalesce((select sum(a.quantity) from order_item_allocations a join order_items i on i.id = a.order_item_id
                                     join orders o on o.id = i.order_id where i.product_id = p.id and o.status = 'PENDING'), 0) as in_transit,
                           coalesce((select sum(a.quantity * a.unit_cost) from order_item_allocations a join order_items i on i.id = a.order_item_id
                                     join orders o on o.id = i.order_id where i.product_id = p.id and o.status = 'PENDING'), 0) as in_transit_value
                    from products p where p.store_id = ?
                ) s
                order by value + in_transit_value desc, name""",
                (rs, i) -> new Wealth.Stock(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), money(rs.getBigDecimal(4)),
                        rs.getInt(5), money(rs.getBigDecimal(6))), storeId)
                .stream().filter(s -> s.available() > 0 || s.inTransit() > 0).toList();

        List<Wealth.Advance> advances = jdbc.query("""
                select p.id, p.name,
                       coalesce(sum(e.amount) filter (where e.currency = 'USD'), 0),
                       coalesce(sum(e.amount) filter (where e.currency = 'SYP'), 0)
                from staff_people p join advance_entries e on e.person_id = p.id
                where p.store_id = ? group by p.id, p.name order by p.name""",
                (rs, i) -> new Wealth.Advance(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), money(usd(rs.getBigDecimal(3), rs.getBigDecimal(4), sell))), storeId)
                .stream().filter(a -> a.usd().signum() != 0 || a.syp().signum() != 0).toList();

        BigDecimal cash = accounts.stream().map(Wealth.Account::valueUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal warehouse = stock.stream().map(Wealth.Stock::valueUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal transit = stock.stream().map(Wealth.Stock::inTransitValueUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal adv = advances.stream().map(Wealth.Advance::valueUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean sypExcluded = sell == null && (accounts.stream().anyMatch(a -> a.syp().signum() != 0)
                || advances.stream().anyMatch(a -> a.syp().signum() != 0));
        Wealth.Totals totals = new Wealth.Totals(money(cash), money(warehouse), money(transit), money(adv),
                money(cash.add(warehouse).add(transit).add(adv)), sypExcluded);
        return new Wealth(totals, accounts, stock, advances, rate);
    }

    @Transactional
    public ProfitShare updateProfitShare(UUID storeId, ProfitShareRequest request) {
        jdbc.update("update stores set profit_share_label = ?, profit_share_percent = ? where id = ?",
                request.label().trim(), request.percent().setScale(2, RoundingMode.HALF_UP), storeId);
        return profitShare(storeId, BigDecimal.ZERO);
    }

    // ---- Parts -----------------------------------------------------------------------

    private ProfitShare profitShare(UUID storeId, BigDecimal netProfit) {
        return jdbc.queryForObject("select profit_share_label, profit_share_percent from stores where id = ?",
                (rs, i) -> {
                    BigDecimal percent = rs.getBigDecimal(2);
                    BigDecimal amount = netProfit.signum() > 0
                            ? netProfit.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2);
                    return new ProfitShare(rs.getString(1), percent.stripTrailingZeros(), amount);
                }, storeId);
    }

    private Pending pending(UUID storeId) {
        return jdbc.queryForObject("""
                select count(*), coalesce(sum(%s), 0),
                       coalesce(sum((select coalesce(sum(a.quantity * a.unit_cost), 0) from order_item_allocations a
                                     join order_items i on i.id = a.order_item_id where i.order_id = o.id)), 0),
                       coalesce(sum(case when o.delivery_cost_currency = 'USD' then o.delivery_cost
                                         when o.delivery_cost > 0 then o.delivery_cost / o.exchange_rate_snapshot else 0 end), 0)
                from orders o where o.store_id = ? and o.status = 'PENDING'""".formatted(REVENUE_USD),
                (rs, i) -> new Pending(rs.getInt(1), money(rs.getBigDecimal(2)),
                        money(rs.getBigDecimal(2).subtract(rs.getBigDecimal(3)).subtract(rs.getBigDecimal(4)))), storeId);
    }

    /** Daily buckets, or monthly beyond ~2 months. Every bucket is present (zeros included) so the chart has no gaps. */
    private List<Point> series(UUID storeId, ZoneId zone, LocalDate from, LocalDate to) {
        boolean monthly = ChronoUnit.DAYS.between(from, to) > 62;
        String unit = monthly ? "month" : "day";
        Map<LocalDate, BigDecimal[]> buckets = new TreeMap<>();
        for (LocalDate d = monthly ? from.withDayOfMonth(1) : from; !d.isAfter(to); d = monthly ? d.plusMonths(1) : d.plusDays(1)) {
            buckets.put(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }
        Timestamp start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        String tz = zone.getId();
        // revenue and cost by delivery day
        jdbc.query("""
                select date_trunc('%s', o.delivered_at at time zone ?)::date,
                       sum(%s),
                       sum((select coalesce(sum(a.quantity * a.unit_cost), 0) from order_item_allocations a
                            join order_items i on i.id = a.order_item_id where i.order_id = o.id))
                from orders o where o.store_id = ? and o.status = 'DELIVERED' and o.delivered_at >= ? and o.delivered_at < ?
                group by 1""".formatted(unit, REVENUE_USD),
                rs -> {
                    BigDecimal[] b = buckets.get(rs.getObject(1, LocalDate.class));
                    if (b != null) {
                        b[0] = b[0].add(rs.getBigDecimal(2));
                        b[1] = b[1].add(rs.getBigDecimal(2)).subtract(rs.getBigDecimal(3));
                    }
                }, tz, storeId, start, end);
        jdbc.query("""
                select date_trunc('%s', expense_date)::date, sum(amount_usd) from expenses
                where store_id = ? and voided_at is null and expense_date between ? and ? group by 1""".formatted(unit),
                rs -> {
                    BigDecimal[] b = buckets.get(rs.getObject(1, LocalDate.class));
                    if (b != null) b[1] = b[1].subtract(rs.getBigDecimal(2));
                }, storeId, from, to);
        List<Point> points = new ArrayList<>();
        buckets.forEach((date, b) -> points.add(new Point(date, money(b[0]), money(b[1]))));
        return points;
    }

    /** Best products by profit in the period: their share of delivered revenue minus their FIFO cost. */
    private List<TopProduct> topProducts(UUID storeId, Timestamp start, Timestamp end) {
        return jdbc.query("""
                select id, name, quantity, revenue, cost from (
                select p.id, p.name, sum(i.quantity) as quantity,
                       sum(case when o.currency = 'USD' then i.unit_price * i.quantity
                                else i.unit_price * i.quantity / o.exchange_rate_snapshot end) as revenue,
                       sum((select coalesce(sum(a.quantity * a.unit_cost), 0) from order_item_allocations a where a.order_item_id = i.id)) as cost
                from order_items i join orders o on o.id = i.order_id join products p on p.id = i.product_id
                where o.store_id = ? and o.status = 'DELIVERED' and o.delivered_at >= ? and o.delivered_at < ?
                group by p.id, p.name
                ) t order by revenue - cost desc limit 5""",
                (rs, i) -> new TopProduct(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), money(rs.getBigDecimal(4)),
                        money(rs.getBigDecimal(4).subtract(rs.getBigDecimal(5)))), storeId, start, end);
    }

    private Returns returns(UUID storeId, Timestamp start, Timestamp end, int delivered) {
        int returned = count("select count(*) from orders where store_id = ? and status = 'RETURNED' and returned_at >= ? and returned_at < ?",
                storeId, start, end);
        BigDecimal cost = sum("""
                select coalesce(sum(e.amount_usd), 0) from expenses e join orders o on o.id = e.order_id
                where o.store_id = ? and o.status = 'RETURNED' and o.returned_at >= ? and o.returned_at < ? and e.voided_at is null""",
                storeId, start, end);
        return new Returns(returned, delivered, money(cost));
    }

    private Alerts alerts(UUID storeId) {
        // Only products that were ever bought: a product never stocked isn't "running low".
        List<LowStock> low = jdbc.query("""
                select p.id, p.name, coalesce(sum(l.qty_left), 0)
                from products p join inventory_lots l on l.product_id = p.id
                where p.store_id = ? and p.deleted_at is null and p.active
                group by p.id, p.name having coalesce(sum(l.qty_left), 0) < ?
                order by 3, p.name""",
                (rs, i) -> new LowStock(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)), storeId, lowStockThreshold);
        Instant now = Instant.now();
        List<LateOrder> late = jdbc.query("""
                select id, order_number, customer_name, ordered_at from orders
                where store_id = ? and status = 'PENDING' and ordered_at < ? order by ordered_at""",
                (rs, i) -> {
                    Instant at = rs.getTimestamp(4).toInstant();
                    return new LateOrder(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), at,
                            Duration.between(at, now).toDays());
                }, storeId, Timestamp.from(now.minus(Duration.ofDays(lateOrderDays))));
        return new Alerts(lowStockThreshold, low, lateOrderDays, late);
    }

    private Rate rate(UUID storeId) {
        CurrentRateResponse current = rates.current(storeId);
        return current.current() == null ? new Rate(null, null, true, current.pendingReview() != null)
                : new Rate(current.current().sellRate(), current.current().rateDate(), current.stale(), current.pendingReview() != null);
    }

    private ZoneId zone(UUID storeId) {
        return ZoneId.of(jdbc.queryForObject("select timezone from stores where id = ?", String.class, storeId));
    }

    private static BigDecimal usd(BigDecimal usd, BigDecimal syp, BigDecimal sellRate) {
        return sellRate == null ? usd : usd.add(syp.divide(sellRate, 10, RoundingMode.HALF_UP));
    }

    private BigDecimal sum(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}

package com.techhouse.finance.application;

import com.techhouse.finance.domain.Partner;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvestorSettlementService {

    public record Invoice(UUID id, String number, BigDecimal fundedUsd, BigDecimal fundingPercent,
                          BigDecimal profitPercent, BigDecimal invoiceProfitUsd, BigDecimal capitalRecoveredUsd,
                          BigDecimal profitUsd) {}

    public record Movement(UUID id, String kind, BigDecimal amountUsd, String account, UUID invoiceId,
                           String note, Instant createdAt, Instant reversedAt) {}

    public record Statement(List<Invoice> invoices, List<Movement> movements, BigDecimal capitalAvailableUsd,
                            BigDecimal profitAvailableUsd) {}

    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final CashAccountService accounts;
    private final LedgerService ledger;

    @Transactional(readOnly = true)
    public Statement statement(UUID storeId, UUID investorId) {
        requireInvestor(storeId, investorId, null);
        return calculate(storeId, investorId);
    }

    @Transactional
    public Statement payout(UUID storeId, UUID investorId, String kind, UUID accountId, BigDecimal amount, String note) {
        requireInvestor(storeId, investorId, LockModeType.PESSIMISTIC_WRITE);
        Statement current = calculate(storeId, investorId);
        requireBalance(amount, kind.equals("CAPITAL_PAYOUT") ? current.capitalAvailableUsd() : current.profitAvailableUsd(), "amount");
        UUID box = accounts.resolve(storeId, accountId, "accountId").getId();
        UUID movement = jdbc.queryForObject("""
                insert into investor_settlements (investor_id, kind, amount_usd, account_id, note)
                values (?, ?, ?, ?, ?) returning id""", UUID.class, investorId, kind, amount, box, note);
        ledger.record(LedgerService.Entry.of(storeId, box, "USD", kind, amount.negate())
                .ref("INVESTOR", investorId).group(movement).note(note));
        return calculate(storeId, investorId);
    }

    @Transactional
    public void lockInvestor(UUID storeId, UUID investorId) {
        requireInvestor(storeId, investorId, LockModeType.PESSIMISTIC_WRITE);
    }

    @Transactional
    public void reinvest(UUID storeId, UUID investorId, UUID paymentId, BigDecimal amount, String field) {
        lockInvestor(storeId, investorId);
        requireBalance(amount, calculate(storeId, investorId).capitalAvailableUsd(), field);
        jdbc.update("""
                insert into investor_settlements (investor_id, kind, amount_usd, payment_id)
                values (?, 'REINVEST', ?, ?)""", investorId, amount, paymentId);
    }

    public void reverseReinvestment(UUID invoiceId) {
        jdbc.update("""
                update investor_settlements set reversed_at = now()
                where reversed_at is null and payment_id in (
                    select id from purchase_invoice_payments where invoice_id = ?
                )""", invoiceId);
    }

    private void requireInvestor(UUID storeId, UUID id, LockModeType lock) {
        Partner partner = lock == null ? em.find(Partner.class, id) : em.find(Partner.class, id, lock);
        if (partner == null || !storeId.equals(partner.getStoreId()) || !"INVESTOR".equals(partner.getKind())) {
            throw new ResourceNotFoundException("PARTNER_NOT_FOUND", "Investor", id);
        }
    }

    private static void requireBalance(BigDecimal amount, BigDecimal available, String field) {
        if (amount.compareTo(available) > 0) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "INVESTOR_BALANCE_INSUFFICIENT",
                    field, "Amount exceeds available investor balance");
        }
    }

    private Statement calculate(UUID storeId, UUID investorId) {
        List<Invoice> invoices = jdbc.query(INVOICES, (rs, row) -> new Invoice(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8)), storeId, investorId, storeId);
        List<Movement> movements = jdbc.query("""
                select s.id, s.kind, s.amount_usd, a.name, p.invoice_id, s.note, s.created_at, s.reversed_at
                from investor_settlements s left join cash_accounts a on a.id = s.account_id
                left join purchase_invoice_payments p on p.id = s.payment_id
                where s.investor_id = ? order by s.created_at desc, s.id""",
                (rs, row) -> {
                    Timestamp reversed = rs.getTimestamp(8);
                    return new Movement(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                            rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getTimestamp(7).toInstant(),
                            reversed == null ? null : reversed.toInstant());
                }, investorId);
        BigDecimal capital = invoices.stream().map(Invoice::capitalRecoveredUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profit = invoices.stream().map(Invoice::profitUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Movement movement : movements) {
            if (movement.reversedAt() != null) continue;
            if ("PROFIT_PAYOUT".equals(movement.kind())) profit = profit.subtract(movement.amountUsd());
            else capital = capital.subtract(movement.amountUsd());
        }
        return new Statement(invoices, movements, capital, profit);
    }

    private static final String INVOICES = """
            with funding as (
                select i.id, i.invoice_number, t.profit_percent,
                       sum(p.amount_usd) as total,
                       coalesce(sum(p.amount_usd) filter (where p.partner_id = t.investor_id and p.source = 'INVESTOR'), 0) as funded
                from investor_invoice_terms t join purchase_invoices i on i.id = t.invoice_id
                join purchase_invoice_payments p on p.invoice_id = i.id
                where i.store_id = ? and t.investor_id = ? and i.voided_at is null
                group by i.id, t.profit_percent
            ), order_parts as (
                select o.id as order_id, i.invoice_id, o.status, o.currency, o.exchange_rate_snapshot,
                       o.delivery_fee_charged,
                       sum(a.quantity * oi.unit_price) as sales, sum(a.quantity * a.unit_cost) as cost
                from orders o join order_items oi on oi.order_id = o.id
                join order_item_allocations a on a.order_item_id = oi.id
                join inventory_lots l on l.id = a.lot_id join invoice_items i on i.id = l.invoice_item_id
                where o.store_id = ? and (o.status = 'DELIVERED' or (o.status = 'RETURNED' and o.return_type = 'UNDELIVERED'))
                group by o.id, i.invoice_id
            ), weighted as (
                select p.*, p.sales / sum(p.sales) over (partition by p.order_id) as weight
                from order_parts p
            ), results as (
                select w.invoice_id,
                       sum(case when w.status = 'DELIVERED' then w.cost else 0 end) as sold_cost,
                       sum(case when w.status = 'DELIVERED' then
                           (w.sales + w.delivery_fee_charged * w.weight) /
                           case when w.currency = 'USD' then 1 else w.exchange_rate_snapshot end - w.cost
                           else 0 end - w.weight * coalesce((
                               select sum(e.amount_usd) from expenses e
                               where e.order_id = w.order_id and e.voided_at is null
                           ), 0)) as profit
                from weighted w join funding f on f.id = w.invoice_id group by w.invoice_id
            )
            select f.id, f.invoice_number, f.funded, round(100 * f.funded / f.total, 4), f.profit_percent,
                   round(coalesce(r.profit, 0), 4),
                   round(least(coalesce(r.sold_cost, 0), f.total) * f.funded / f.total, 4),
                   round(coalesce(r.profit, 0) * f.funded / f.total * f.profit_percent / 100, 4)
            from funding f left join results r on r.invoice_id = f.id
            where f.funded > 0 order by f.invoice_number
            """;
}

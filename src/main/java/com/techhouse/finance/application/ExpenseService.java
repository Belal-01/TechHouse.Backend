package com.techhouse.finance.application;

import com.techhouse.finance.domain.CashAccount;
import com.techhouse.finance.domain.Expense;
import com.techhouse.finance.domain.ExpenseCategory;
import com.techhouse.finance.persistence.ExpenseCategoryRepository;
import com.techhouse.finance.web.dto.ExpenseDtos;
import com.techhouse.finance.web.dto.ExpenseDtos.ExpenseResponse;
import com.techhouse.finance.web.dto.ExpenseDtos.Ref;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import com.techhouse.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Expenses in their own currency, valued in USD at the sell rate, paid from a box (with its ledger entry) or an advance. */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final int MAX_PAGE_SIZE = 100;

    /** What to record. {@code accountId} null = paid out of a staff advance (no cash leaves a box now). */
    public record Draft(UUID storeId, UUID orderId, UUID categoryId, String description, BigDecimal amount,
                        String currency, UUID accountId, UUID rateId, BigDecimal rate, LocalDate date) {}

    private final EntityManager em;
    private final LedgerService ledger;
    private final ExpenseCategoryRepository categories;
    private final CashAccountService accounts;
    private final ExchangeRateService rates;
    private final JdbcTemplate jdbc;

    // ---- Used by other modules ------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY)
    public Expense record(Draft d) {
        Expense e = new Expense();
        e.setStoreId(d.storeId());
        e.setOrderId(d.orderId());
        e.setCategoryId(d.categoryId());
        e.setDescription(d.description());
        e.setAmount(d.amount().setScale(4, RoundingMode.HALF_UP));
        e.setCurrency(d.currency());
        e.setAccountId(d.accountId());
        e.setExchangeRateId(d.rateId());
        e.setExchangeRateSnapshot(d.rate());
        e.setAmountUsd(toUsd(d.amount(), d.currency(), d.rate()));
        e.setExpenseDate(d.date());
        em.persist(e);
        if (d.accountId() != null) {
            ledger.record(LedgerService.Entry.of(d.storeId(), d.accountId(), d.currency(), "EXPENSE", e.getAmount().negate())
                    .rate(d.rateId(), d.rate()).ref("EXPENSE", e.getId()).note(d.description()));
        }
        return e;
    }

    /** The id of a category the app relies on, e.g. {@link ExpenseCategory#DELIVERY}. */
    @Transactional(readOnly = true)
    public UUID systemCategory(UUID storeId, String key) {
        return categories.findByStoreIdAndSystemKey(storeId, key).map(ExpenseCategory::getId)
                .orElseThrow(() -> new IllegalStateException("Missing expense category " + key + " for store " + storeId));
    }

    /** Voids an order's expenses and gives the money back to the boxes that paid them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void voidForOrder(UUID storeId, UUID orderId, String note) {
        em.createQuery("select e from Expense e where e.storeId = :store and e.orderId = :order and e.voidedAt is null",
                        Expense.class)
                .setParameter("store", storeId).setParameter("order", orderId).getResultList()
                .forEach(e -> reverse(e, note));
    }

    // ---- The expenses screen ---------------------------------------------------------

    @Transactional
    public ExpenseResponse create(UUID storeId, ExpenseDtos.ExpenseRequest request) {
        ExpenseCategory category = activeCategory(storeId, request.categoryId());
        CashAccount account = accounts.resolve(storeId, request.accountId(), "accountId");
        ExchangeRateService.SaleRate rate = rateFor(storeId, request.currency());
        String description = blankToNull(request.description());
        Expense e = record(new Draft(storeId, null, category.getId(), description == null ? category.getName() : description,
                request.amount(), request.currency(), account.getId(), rate == null ? null : rate.id(),
                rate == null ? null : rate.sellRate(), request.expenseDate()));
        em.flush();
        return get(storeId, e.getId());
    }

    /** Undo a mistaken expense: kept and flagged, its money returned to the box. Order fees go with their order. */
    @Transactional
    public ExpenseResponse voidExpense(UUID storeId, UUID id, String note) {
        Expense e = em.find(Expense.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (e == null || !e.getStoreId().equals(storeId)) throw new ResourceNotFoundException("EXPENSE_NOT_FOUND", "Expense", id);
        if (e.getVoidedAt() != null) throw new BusinessException(HttpStatus.CONFLICT, "EXPENSE_ALREADY_VOIDED", "Already voided");
        if (e.getOrderId() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXPENSE_BELONGS_TO_ORDER",
                    "Order delivery fees are voided with the order");
        }
        if (e.getAccountId() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXPENSE_FROM_ADVANCE",
                    "Expenses paid from an advance can't be voided here");
        }
        reverse(e, blankToNull(note) == null ? "إلغاء مصروف" : note.trim());
        em.flush();
        return get(storeId, id);
    }

    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> list(UUID storeId, UUID categoryId, LocalDate from, LocalDate to, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Filter f = filter(storeId, categoryId, from, to);
        long total = jdbc.queryForObject("select count(*) from expenses e where " + f.where, Long.class, f.args.toArray());
        List<Object> args = new ArrayList<>(f.args);
        args.add(s);
        args.add((long) p * s);
        List<ExpenseResponse> rows = jdbc.query(SELECT + " where " + f.where
                + " order by e.expense_date desc, e.created_at desc limit ? offset ?", (rs, i) -> map(rs), args.toArray());
        return PageResponse.from(new PageImpl<>(rows, PageRequest.of(p, s), total));
    }

    @Transactional(readOnly = true)
    public ExpenseDtos.Summary summary(UUID storeId, LocalDate from, LocalDate to) {
        Filter f = filter(storeId, null, from, to);
        List<ExpenseDtos.CategoryTotal> totals = jdbc.query("""
                select c.id, c.name, sum(e.amount_usd), count(*)
                from expenses e join expense_categories c on c.id = e.category_id
                where %s and e.voided_at is null
                group by c.id, c.name order by sum(e.amount_usd) desc""".formatted(f.where),
                (rs, i) -> new ExpenseDtos.CategoryTotal(new Ref(rs.getObject(1, UUID.class), rs.getString(2)),
                        rs.getBigDecimal(3), rs.getInt(4)), f.args.toArray());
        BigDecimal total = totals.stream().map(ExpenseDtos.CategoryTotal::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ExpenseDtos.Summary(from, to, total, totals);
    }

    @Transactional(readOnly = true)
    public ExpenseResponse get(UUID storeId, UUID id) {
        List<ExpenseResponse> rows = jdbc.query(SELECT + " where e.store_id = ? and e.id = ?", (rs, i) -> map(rs), storeId, id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("EXPENSE_NOT_FOUND", "Expense", id);
        return rows.getFirst();
    }

    // ---- Internals -------------------------------------------------------------------

    private void reverse(Expense e, String note) {
        e.setVoidedAt(Instant.now());
        if (e.getAccountId() != null) {
            ledger.record(LedgerService.Entry.of(e.getStoreId(), e.getAccountId(), e.getCurrency(), "ADJUSTMENT", e.getAmount())
                    .rate(e.getExchangeRateId(), e.getExchangeRateSnapshot()).ref("EXPENSE", e.getId()).note(note));
        }
    }

    ExpenseCategory activeCategory(UUID storeId, UUID categoryId) {
        ExpenseCategory c = categories.findByIdAndStoreId(categoryId, storeId)
                .orElseThrow(() -> BusinessException.onField(HttpStatus.NOT_FOUND, "EXPENSE_CATEGORY_NOT_FOUND", "categoryId",
                        "Expense category not found"));
        if (!c.isActive()) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "EXPENSE_CATEGORY_INACTIVE", "categoryId", "Category is inactive");
        }
        return c;
    }

    /** The sell rate for valuing an amount: required for SYP, kept for USD when there is one. */
    ExchangeRateService.SaleRate rateFor(UUID storeId, String currency) {
        ExchangeRateService.SaleRate rate = rates.saleRate(storeId).orElse(null);
        if (rate == null && "SYP".equals(currency)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXCHANGE_RATE_REQUIRED", "No exchange rate yet; enter today's rate first");
        }
        return rate;
    }

    private record Filter(String where, List<Object> args) {}

    private static Filter filter(UUID storeId, UUID categoryId, LocalDate from, LocalDate to) {
        StringBuilder where = new StringBuilder("e.store_id = ?");
        List<Object> args = new ArrayList<>(List.of(storeId));
        if (categoryId != null) {
            where.append(" and e.category_id = ?");
            args.add(categoryId);
        }
        if (from != null) {
            where.append(" and e.expense_date >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" and e.expense_date <= ?");
            args.add(to);
        }
        return new Filter(where.toString(), args);
    }

    private static final String SELECT = """
            select e.id, e.expense_date, c.id, c.name, e.description, e.amount, e.currency, e.exchange_rate_snapshot,
                   e.amount_usd, a.id, a.name, e.order_id, p.id, p.name, e.voided_at, e.created_at
            from expenses e
            join expense_categories c on c.id = e.category_id
            left join cash_accounts a on a.id = e.account_id
            left join advance_entries ae on ae.expense_id = e.id
            left join staff_people p on p.id = ae.person_id""";

    private static ExpenseResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID accountId = rs.getObject(10, UUID.class);
        UUID personId = rs.getObject(13, UUID.class);
        java.sql.Timestamp voided = rs.getTimestamp(15);
        return new ExpenseResponse(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                new Ref(rs.getObject(3, UUID.class), rs.getString(4)), rs.getString(5), rs.getBigDecimal(6),
                rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                accountId == null ? null : new Ref(accountId, rs.getString(11)), rs.getObject(12, UUID.class),
                personId == null ? null : new Ref(personId, rs.getString(14)),
                voided == null ? null : voided.toInstant(), rs.getTimestamp(16).toInstant());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static BigDecimal toUsd(BigDecimal amount, String currency, BigDecimal rate) {
        return "USD".equals(currency) ? amount.setScale(4, RoundingMode.HALF_UP)
                : amount.divide(rate, 4, RoundingMode.HALF_UP);
    }
}

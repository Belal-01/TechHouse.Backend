package com.techhouse.finance.application;

import com.techhouse.finance.domain.CashAccount;
import com.techhouse.finance.web.dto.MovementDtos;
import com.techhouse.finance.web.dto.MovementDtos.MovementResponse;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owner-entered cash movements (capital, transfers, exchanges, corrections) and the ledger listing. */
@Service
@RequiredArgsConstructor
public class CashMovementService {

    private static final int MAX_PAGE_SIZE = 100;

    private final LedgerService ledger;
    private final CashAccountService accounts;
    private final ExchangeRateService rates;
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    @Transactional
    public void capital(UUID storeId, MovementDtos.CapitalRequest r) {
        CashAccount account = accounts.resolve(storeId, r.accountId(), "accountId");
        ledger.record(LedgerService.Entry.of(storeId, account.getId(), r.currency(), "CAPITAL_IN", scale(r.amount()))
                .note(blankToNull(r.note())));
    }

    @Transactional
    public void transfer(UUID storeId, MovementDtos.TransferRequest r) {
        if (Objects.equals(r.fromAccountId(), r.toAccountId())) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT", "toAccountId", "Choose two different boxes");
        }
        CashAccount from = accounts.resolve(storeId, r.fromAccountId(), "fromAccountId");
        CashAccount to = accounts.resolve(storeId, r.toAccountId(), "toAccountId");
        UUID group = UUID.randomUUID();
        String note = blankToNull(r.note());
        ledger.record(LedgerService.Entry.of(storeId, from.getId(), r.currency(), "TRANSFER_OUT", scale(r.amount()).negate())
                .group(group).note(note));
        ledger.record(LedgerService.Entry.of(storeId, to.getId(), r.currency(), "TRANSFER_IN", scale(r.amount()))
                .group(group).note(note));
    }

    /** The rate actually obtained (SYP per USD) is kept on both halves, for the revaluation report later. */
    @Transactional
    public void exchange(UUID storeId, MovementDtos.ExchangeRequest r) {
        CashAccount from = accounts.resolve(storeId, r.fromAccountId(), "fromAccountId");
        CashAccount to = accounts.resolve(storeId, r.toAccountId(), "toAccountId");
        String toCurrency = "USD".equals(r.fromCurrency()) ? "SYP" : "USD";
        BigDecimal syp = "SYP".equals(r.fromCurrency()) ? r.fromAmount() : r.toAmount();
        BigDecimal usd = "USD".equals(r.fromCurrency()) ? r.fromAmount() : r.toAmount();
        BigDecimal rate = syp.divide(usd, 4, RoundingMode.HALF_UP);
        UUID group = UUID.randomUUID();
        String note = blankToNull(r.note());
        ledger.record(LedgerService.Entry.of(storeId, from.getId(), r.fromCurrency(), "EXCHANGE_OUT", scale(r.fromAmount()).negate())
                .rate(null, rate).group(group).note(note));
        ledger.record(LedgerService.Entry.of(storeId, to.getId(), toCurrency, "EXCHANGE_IN", scale(r.toAmount()))
                .rate(null, rate).group(group).note(note));
    }

    @Transactional
    public void adjustment(UUID storeId, MovementDtos.AdjustmentRequest r) {
        if (r.amount().signum() == 0) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "amount", "Amount can't be zero");
        }
        CashAccount account = accounts.resolve(storeId, r.accountId(), "accountId");
        ledger.record(LedgerService.Entry.of(storeId, account.getId(), r.currency(), "ADJUSTMENT", scale(r.amount()))
                .note(r.note().trim()));
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementResponse> list(UUID storeId, UUID accountId, String currency, String type, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        StringBuilder where = new StringBuilder("m.store_id = ?");
        List<Object> args = new ArrayList<>(List.of(storeId));
        if (accountId != null) {
            where.append(" and m.account_id = ?");
            args.add(accountId);
        }
        if (currency != null && !currency.isBlank()) {
            where.append(" and m.wallet_currency = ?");
            args.add(currency);
        }
        if (type != null && !type.isBlank()) {
            where.append(" and m.movement_type = ?");
            args.add(type);
        }
        long total = jdbc.queryForObject("select count(*) from cash_movements m where " + where, Long.class, args.toArray());
        args.add(s);
        args.add((long) p * s);
        // The label of what caused each movement: order / invoice number, expense description, person name.
        List<MovementResponse> rows = jdbc.query("""
                select m.id, a.id, a.name, m.wallet_currency, m.movement_type, m.amount, m.exchange_rate_snapshot,
                       m.reference_type, m.reference_id,
                       coalesce(o.order_number, i.invoice_number, e.description, sp.name), m.exchange_group, m.note, m.created_at
                from cash_movements m
                join cash_accounts a on a.id = m.account_id
                left join orders o on m.reference_type = 'ORDER' and o.id = m.reference_id
                left join purchase_invoices i on m.reference_type = 'PURCHASE_INVOICE' and i.id = m.reference_id
                left join expenses e on m.reference_type = 'EXPENSE' and e.id = m.reference_id
                left join staff_people sp on m.reference_type = 'PERSON' and sp.id = m.reference_id
                where %s
                order by m.created_at desc, m.id desc limit ? offset ?""".formatted(where),
                (rs, i) -> {
                    String refType = rs.getString(8);
                    return new MovementResponse(rs.getObject(1, UUID.class),
                            new MovementDtos.Ref(rs.getObject(2, UUID.class), rs.getString(3)), rs.getString(4), rs.getString(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7),
                            refType == null ? null : new MovementDtos.Reference(refType, rs.getObject(9, UUID.class), rs.getString(10)),
                            rs.getObject(11, UUID.class), rs.getString(12), rs.getTimestamp(13).toInstant());
                }, args.toArray());
        return PageResponse.from(new PageImpl<>(rows, PageRequest.of(p, s), total));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

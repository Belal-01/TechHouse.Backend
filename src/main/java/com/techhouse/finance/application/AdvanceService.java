package com.techhouse.finance.application;

import com.techhouse.finance.domain.AdvanceEntry;
import com.techhouse.finance.domain.CashAccount;
import com.techhouse.finance.domain.Expense;
import com.techhouse.finance.domain.ExpenseCategory;
import com.techhouse.finance.domain.StaffPerson;
import com.techhouse.finance.web.dto.AdvanceDtos;
import com.techhouse.finance.web.dto.MovementDtos;
import com.techhouse.identity.config.CurrentUser;
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
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Advances: money a person (e.g. a manager) took from a box. It is still the shop's money, so it is not an expense
 * and doesn't touch profit. It is settled by returning it (back into a box) or by spending it on the shop (an expense).
 */
@Service
@RequiredArgsConstructor
public class AdvanceService {

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final CashAccountService accounts;
    private final ExpenseService expenses;

    // ---- People ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdvanceDtos.PersonResponse> people(UUID storeId) {
        Map<String, BigDecimal> owed = new HashMap<>();
        jdbc.query("select person_id, currency, sum(amount) from advance_entries where store_id = ? group by 1, 2",
                rs -> {
                    owed.put(rs.getString(1) + rs.getString(2), rs.getBigDecimal(3));
                }, storeId);
        return em.createQuery("select p from StaffPerson p where p.storeId = :store order by p.name", StaffPerson.class)
                .setParameter("store", storeId).getResultList().stream()
                .map(p -> new AdvanceDtos.PersonResponse(p.getId(), p.getName(), p.getPhone(), p.isActive(),
                        owed.getOrDefault(p.getId() + "USD", BigDecimal.ZERO), owed.getOrDefault(p.getId() + "SYP", BigDecimal.ZERO),
                        p.getVersion()))
                .toList();
    }

    @Transactional
    public AdvanceDtos.PersonResponse createPerson(UUID storeId, AdvanceDtos.PersonRequest r) {
        String name = r.name().trim();
        requireUniqueName(storeId, name, null);
        StaffPerson p = new StaffPerson();
        p.setStoreId(storeId);
        apply(p, r, name);
        em.persist(p);
        em.flush();
        return person(storeId, p.getId());
    }

    @Transactional
    public AdvanceDtos.PersonResponse updatePerson(UUID storeId, UUID id, AdvanceDtos.PersonRequest r) {
        StaffPerson p = find(storeId, id, false);
        if (r.version() == null || !r.version().equals(p.getVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The record was modified by someone else");
        }
        String name = r.name().trim();
        requireUniqueName(storeId, name, id);
        apply(p, r, name);
        em.flush();
        return person(storeId, id);
    }

    @Transactional(readOnly = true)
    public AdvanceDtos.PersonResponse person(UUID storeId, UUID id) {
        return people(storeId).stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("PERSON_NOT_FOUND", "Person", id));
    }

    // ---- Advances --------------------------------------------------------------------

    @Transactional
    public AdvanceDtos.PersonResponse withdraw(UUID storeId, UUID personId, AdvanceDtos.CashRequest r) {
        StaffPerson p = find(storeId, personId, true);
        if (!p.isActive()) throw new BusinessException(HttpStatus.CONFLICT, "PERSON_INACTIVE", "This person is inactive");
        CashAccount account = accounts.resolve(storeId, r.accountId(), "accountId");
        BigDecimal amount = scale(r.amount());
        entry(storeId, p, AdvanceEntry.Kind.WITHDRAWAL, r.currency(), amount, account.getId(), null, r.note());
        ledger.record(LedgerService.Entry.of(storeId, account.getId(), r.currency(), "ADVANCE_OUT", amount.negate())
                .ref("PERSON", p.getId()).note(blankToNull(r.note())));
        em.flush();
        return person(storeId, personId);
    }

    @Transactional
    public AdvanceDtos.PersonResponse giveBack(UUID storeId, UUID personId, AdvanceDtos.CashRequest r) {
        StaffPerson p = find(storeId, personId, true);
        BigDecimal amount = scale(r.amount());
        requireOwed(storeId, personId, r.currency(), amount);
        CashAccount account = accounts.resolve(storeId, r.accountId(), "accountId");
        entry(storeId, p, AdvanceEntry.Kind.RETURN, r.currency(), amount.negate(), account.getId(), null, r.note());
        ledger.record(LedgerService.Entry.of(storeId, account.getId(), r.currency(), "ADVANCE_RETURN", amount)
                .ref("PERSON", p.getId()).note(blankToNull(r.note())));
        em.flush();
        return person(storeId, personId);
    }

    @Transactional
    public AdvanceDtos.PersonResponse spend(UUID storeId, UUID personId, AdvanceDtos.SpendRequest r) {
        StaffPerson p = find(storeId, personId, true);
        BigDecimal amount = scale(r.amount());
        requireOwed(storeId, personId, r.currency(), amount);
        ExpenseCategory category = expenses.activeCategory(storeId, r.categoryId());
        ExchangeRateService.SaleRate rate = expenses.rateFor(storeId, r.currency());
        String description = blankToNull(r.description()) == null ? category.getName() : r.description().trim();
        Expense e = expenses.record(new ExpenseService.Draft(storeId, null, category.getId(), description, amount,
                r.currency(), null, rate == null ? null : rate.id(), rate == null ? null : rate.sellRate(), r.expenseDate()));
        entry(storeId, p, AdvanceEntry.Kind.EXPENSE, r.currency(), amount.negate(), null, e.getId(), description);
        return person(storeId, personId);
    }

    @Transactional(readOnly = true)
    public List<AdvanceDtos.EntryResponse> entries(UUID storeId, UUID personId) {
        find(storeId, personId, false);
        return jdbc.query("""
                select e.id, e.kind, e.currency, e.amount, a.id, a.name, e.expense_id, e.note, e.created_at
                from advance_entries e left join cash_accounts a on a.id = e.account_id
                where e.store_id = ? and e.person_id = ? order by e.created_at desc""",
                (rs, i) -> {
                    UUID accountId = rs.getObject(5, UUID.class);
                    return new AdvanceDtos.EntryResponse(rs.getObject(1, UUID.class), AdvanceEntry.Kind.valueOf(rs.getString(2)),
                            rs.getString(3), rs.getBigDecimal(4),
                            accountId == null ? null : new MovementDtos.Ref(accountId, rs.getString(6)),
                            rs.getObject(7, UUID.class), rs.getString(8), rs.getTimestamp(9).toInstant());
                }, storeId, personId);
    }

    // ---- Internals -------------------------------------------------------------------

    /** Returning or spending more than the person holds would make the shop owe them: refused. */
    private void requireOwed(UUID storeId, UUID personId, String currency, BigDecimal amount) {
        BigDecimal owed = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from advance_entries where store_id = ? and person_id = ? and currency = ?",
                BigDecimal.class, storeId, personId, currency);
        if (amount.compareTo(owed) > 0) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "ADVANCE_EXCEEDS_BALANCE", "amount",
                    "Only " + owed.stripTrailingZeros().toPlainString() + " " + currency + " is outstanding");
        }
    }

    private void entry(UUID storeId, StaffPerson p, AdvanceEntry.Kind kind, String currency, BigDecimal amount,
                       UUID accountId, UUID expenseId, String note) {
        AdvanceEntry e = new AdvanceEntry();
        e.setStoreId(storeId);
        e.setPersonId(p.getId());
        e.setKind(kind);
        e.setCurrency(currency);
        e.setAmount(amount);
        e.setAccountId(accountId);
        e.setExpenseId(expenseId);
        e.setNote(blankToNull(note));
        e.setCreatedBy(CurrentUser.idOrNull());
        em.persist(e);
        // Balances are read with SQL right after; make this entry (and its ledger row) visible to it.
        em.flush();
    }

    /** {@code lock}: serialise changes to one person's balance, so two returns can't both pass the check. */
    private StaffPerson find(UUID storeId, UUID id, boolean lock) {
        StaffPerson p = lock ? em.find(StaffPerson.class, id, LockModeType.PESSIMISTIC_WRITE) : em.find(StaffPerson.class, id);
        if (p == null || !p.getStoreId().equals(storeId)) throw new ResourceNotFoundException("PERSON_NOT_FOUND", "Person", id);
        return p;
    }

    private void requireUniqueName(UUID storeId, String name, UUID exceptId) {
        Long n = jdbc.queryForObject("select count(*) from staff_people where store_id = ? and lower(name) = lower(?) and id <> ?",
                Long.class, storeId, name, exceptId == null ? new UUID(0, 0) : exceptId);
        if (n != null && n > 0) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "PERSON_NAME_EXISTS", "name", "A person with this name exists");
        }
    }

    private static void apply(StaffPerson p, AdvanceDtos.PersonRequest r, String name) {
        p.setName(name);
        p.setPhone(blankToNull(r.phone()));
        if (r.active() != null) p.setActive(r.active());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

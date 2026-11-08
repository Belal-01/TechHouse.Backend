package com.techhouse.finance.application;

import com.techhouse.finance.domain.Expense;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Records expenses together with their cash movement, so the wallet and the expense list always agree. */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final EntityManager em;
    private final LedgerService ledger;

    /** SYP amounts are valued in USD at {@code rate} (SYP per USD). Paid from the wallet of the same currency. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Expense record(UUID storeId, UUID orderId, String category, String description, BigDecimal amount,
                          String currency, UUID rateId, BigDecimal rate, LocalDate date) {
        Expense e = new Expense();
        e.setStoreId(storeId);
        e.setOrderId(orderId);
        e.setCategory(category);
        e.setDescription(description);
        e.setAmount(amount.setScale(4, RoundingMode.HALF_UP));
        e.setCurrency(currency);
        e.setExchangeRateId(rateId);
        e.setExchangeRateSnapshot(rate);
        e.setAmountUsd(toUsd(amount, currency, rate));
        e.setExpenseDate(date);
        em.persist(e);
        ledger.record(storeId, currency, "EXPENSE", e.getAmount().negate(), rateId, rate, "EXPENSE", e.getId(), description);
        return e;
    }

    /** Voids an order's expenses and gives the money back to the wallets with a reversing ledger entry. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void voidForOrder(UUID storeId, UUID orderId, String note) {
        List<Expense> expenses = em.createQuery(
                        "select e from Expense e where e.storeId = :store and e.orderId = :order and e.voidedAt is null",
                        Expense.class)
                .setParameter("store", storeId).setParameter("order", orderId).getResultList();
        for (Expense e : expenses) {
            e.setVoidedAt(Instant.now());
            ledger.record(storeId, e.getCurrency(), "ADJUSTMENT", e.getAmount(), e.getExchangeRateId(),
                    e.getExchangeRateSnapshot(), "EXPENSE", e.getId(), note);
        }
    }

    public static BigDecimal toUsd(BigDecimal amount, String currency, BigDecimal rate) {
        return "USD".equals(currency) ? amount.setScale(4, RoundingMode.HALF_UP)
                : amount.divide(rate, 4, RoundingMode.HALF_UP);
    }
}

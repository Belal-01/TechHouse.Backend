package com.techhouse.finance.application;

import com.techhouse.finance.domain.CashMovement;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** Single entry point for writing the cash ledger, so every module records movements the same way. */
@Service
@RequiredArgsConstructor
public class LedgerService {

    /**
     * One ledger row. {@code amount} is signed (+ into the box, − out of it), in {@code currency}.
     * {@code rate} is the SYP-per-USD rate the amount was valued at, when relevant.
     */
    public record Entry(UUID storeId, UUID accountId, String currency, String type, BigDecimal amount,
                        UUID rateId, BigDecimal rate, String referenceType, UUID referenceId, UUID group, String note) {

        public static Entry of(UUID storeId, UUID accountId, String currency, String type, BigDecimal amount) {
            return new Entry(storeId, accountId, currency, type, amount, null, null, null, null, null, null);
        }

        public Entry rate(UUID rateId, BigDecimal rate) {
            return new Entry(storeId, accountId, currency, type, amount, rateId, rate, referenceType, referenceId, group, note);
        }

        public Entry ref(String referenceType, UUID referenceId) {
            return new Entry(storeId, accountId, currency, type, amount, rateId, rate, referenceType, referenceId, group, note);
        }

        public Entry group(UUID group) {
            return new Entry(storeId, accountId, currency, type, amount, rateId, rate, referenceType, referenceId, group, note);
        }

        public Entry note(String note) {
            return new Entry(storeId, accountId, currency, type, amount, rateId, rate, referenceType, referenceId, group, note);
        }
    }

    private final EntityManager em;

    /** Joins the caller's transaction: the movement and the business record commit or roll back together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CashMovement record(Entry e) {
        CashMovement m = new CashMovement();
        m.setStoreId(e.storeId());
        m.setAccountId(e.accountId());
        m.setWalletCurrency(e.currency());
        m.setMovementType(e.type());
        m.setAmount(e.amount());
        m.setExchangeRateId(e.rateId());
        m.setExchangeRateSnapshot(e.rate());
        m.setReferenceType(e.referenceType());
        m.setReferenceId(e.referenceId());
        m.setExchangeGroup(e.group());
        m.setNote(e.note());
        em.persist(m);
        return m;
    }
}

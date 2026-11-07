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

    private final EntityManager em;

    /** Joins the caller's transaction: the movement and the business record commit or roll back together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID storeId, String walletCurrency, String movementType, BigDecimal signedAmount,
                       String referenceType, UUID referenceId, String note) {
        CashMovement m = new CashMovement();
        m.setStoreId(storeId);
        m.setWalletCurrency(walletCurrency);
        m.setMovementType(movementType);
        m.setAmount(signedAmount);
        m.setReferenceType(referenceType);
        m.setReferenceId(referenceId);
        m.setNote(note);
        em.persist(m);
    }
}

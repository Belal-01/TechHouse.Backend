package com.techhouse.finance.persistence;

import com.techhouse.finance.domain.CashAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashAccountRepository extends JpaRepository<CashAccount, UUID> {

    List<CashAccount> findByStoreIdOrderByDefaultAccountDescNameAsc(UUID storeId);

    Optional<CashAccount> findByIdAndStoreId(UUID id, UUID storeId);

    Optional<CashAccount> findByStoreIdAndDefaultAccountTrue(UUID storeId);

    boolean existsByStoreIdAndNameIgnoreCase(UUID storeId, String name);

    boolean existsByStoreIdAndNameIgnoreCaseAndIdNot(UUID storeId, String name, UUID id);
}

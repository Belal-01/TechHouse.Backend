package com.techhouse.finance.persistence;

import com.techhouse.finance.domain.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

    List<ExpenseCategory> findByStoreIdOrderByNameAsc(UUID storeId);

    Optional<ExpenseCategory> findByIdAndStoreId(UUID id, UUID storeId);

    Optional<ExpenseCategory> findByStoreIdAndSystemKey(UUID storeId, String systemKey);

    boolean existsByStoreIdAndNameIgnoreCase(UUID storeId, String name);

    boolean existsByStoreIdAndNameIgnoreCaseAndIdNot(UUID storeId, String name, UUID id);
}

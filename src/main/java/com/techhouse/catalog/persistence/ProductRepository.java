package com.techhouse.catalog.persistence;

import com.techhouse.catalog.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByIdAndStoreIdAndDeletedAtIsNull(UUID id, UUID storeId);

    boolean existsByIdAndStoreId(UUID id, UUID storeId);

    long countByCategoryIdAndDeletedAtIsNull(UUID categoryId);

    /** Soft-deleted products keep no category once the category itself is deleted. */
    @Modifying
    @Query("update Product p set p.categoryId = null where p.categoryId = :categoryId")
    int clearCategory(@Param("categoryId") UUID categoryId);
}

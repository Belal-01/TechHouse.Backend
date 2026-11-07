package com.techhouse.procurement.persistence;

import com.techhouse.procurement.domain.PurchaseInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, UUID> {

    Optional<PurchaseInvoice> findByIdAndStoreId(UUID id, UUID storeId);

    @Query("""
            select i from PurchaseInvoice i
            where i.storeId = :storeId
              and (cast(:supplierId as uuid) is null or i.supplierId = :supplierId)
            """)
    Page<PurchaseInvoice> search(@Param("storeId") UUID storeId, @Param("supplierId") UUID supplierId,
                                 Pageable pageable);
}

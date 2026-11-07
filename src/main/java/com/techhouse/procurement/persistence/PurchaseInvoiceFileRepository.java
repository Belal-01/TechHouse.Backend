package com.techhouse.procurement.persistence;

import com.techhouse.procurement.domain.PurchaseInvoiceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PurchaseInvoiceFileRepository extends JpaRepository<PurchaseInvoiceFile, UUID> {

    /** Ids only, so listing invoices never loads the PDF bytes. */
    @Query("select f.invoiceId from PurchaseInvoiceFile f where f.invoiceId in :ids")
    List<UUID> findInvoiceIdsWithFile(@Param("ids") Collection<UUID> ids);
}

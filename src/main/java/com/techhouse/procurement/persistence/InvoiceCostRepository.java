package com.techhouse.procurement.persistence;

import com.techhouse.procurement.domain.InvoiceCost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InvoiceCostRepository extends JpaRepository<InvoiceCost, UUID> {

    List<InvoiceCost> findByInvoiceIdInOrderByCreatedAtAsc(Collection<UUID> invoiceIds);
}

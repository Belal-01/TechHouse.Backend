package com.techhouse.procurement.persistence;

import com.techhouse.procurement.domain.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    List<InvoiceItem> findByInvoiceIdIn(Collection<UUID> invoiceIds);
}

package com.techhouse.procurement.persistence;

import com.techhouse.procurement.domain.InvoicePayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, UUID> {

    List<InvoicePayment> findByInvoiceIdInOrderByCreatedAtAsc(Collection<UUID> invoiceIds);
}

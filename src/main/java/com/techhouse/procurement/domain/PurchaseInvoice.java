package com.techhouse.procurement.domain;

import com.techhouse.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "purchase_invoices")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseInvoice extends BaseEntity {

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "supplier_id", updatable = false)
    private UUID supplierId;

    @Column(name = "invoice_number", nullable = false, updatable = false, length = 30)
    private String invoiceNumber;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    /** IN_TRANSIT: paid, on its way, not sellable yet. RECEIVED: arrived, its units are in stock. */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "received_at")
    private java.time.Instant receivedAt;

    private String notes;

    /** Set when the invoice was voided (entered by mistake); it stays for the record. */
    @Column(name = "voided_at")
    private java.time.Instant voidedAt;

    @Column(name = "void_note")
    private String voidNote;
}

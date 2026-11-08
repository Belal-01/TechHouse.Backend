package com.techhouse.procurement.domain;

import com.techhouse.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
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

    @Column(name = "shipping_cost_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal shippingCostUsd = BigDecimal.ZERO;

    @Column(name = "customs_cost_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal customsCostUsd = BigDecimal.ZERO;

    @Column(name = "paid_from", nullable = false, length = 20)
    private String paidFrom;

    private String notes;

    /** Set when the invoice was voided (entered by mistake); it stays for the record. */
    @Column(name = "voided_at")
    private java.time.Instant voidedAt;

    @Column(name = "void_note")
    private String voidNote;

    /** The box the invoice was paid from. */
    @Column(name = "account_id", updatable = false)
    private UUID accountId;
}

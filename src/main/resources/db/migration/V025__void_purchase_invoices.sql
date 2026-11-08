-- A purchase invoice entered by mistake is voided, never deleted: its stock leaves, its payment is reversed in the ledger.
ALTER TABLE purchase_invoices
    ADD COLUMN voided_at TIMESTAMPTZ,
    ADD COLUMN void_note TEXT;

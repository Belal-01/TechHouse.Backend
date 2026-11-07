-- The original supplier invoice (PDF) kept as a reference next to the structured data
CREATE TABLE purchase_invoice_files (
    invoice_id      UUID PRIMARY KEY REFERENCES purchase_invoices(id) ON DELETE CASCADE,
    file_name       VARCHAR(255)  NOT NULL,
    content_type    VARCHAR(100)  NOT NULL,
    size_bytes      INTEGER       NOT NULL,
    data            BYTEA         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_invoices_store_date ON purchase_invoices(store_id, invoice_date DESC);

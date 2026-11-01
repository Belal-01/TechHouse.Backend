-- Sequence for human-readable invoice numbers
CREATE SEQUENCE purchase_invoice_seq START 1;

CREATE TABLE purchase_invoices (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id            UUID          NOT NULL REFERENCES stores(id),
    supplier_id         UUID          REFERENCES suppliers(id),
    invoice_number      VARCHAR(30)   NOT NULL,
    invoice_date        DATE          NOT NULL,
    shipping_cost_usd   NUMERIC(19,4) NOT NULL DEFAULT 0,
    customs_cost_usd    NUMERIC(19,4) NOT NULL DEFAULT 0,
    paid_from           VARCHAR(20)   NOT NULL,
    notes               TEXT,
    version             INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_invoice_number UNIQUE (store_id, invoice_number),
    CONSTRAINT chk_paid_from CHECK (paid_from IN ('INCOME', 'EXTERNAL_CAPITAL')),
    CONSTRAINT chk_shipping_positive CHECK (shipping_cost_usd >= 0),
    CONSTRAINT chk_customs_positive CHECK (customs_cost_usd >= 0)
);

CREATE TABLE invoice_items (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id          UUID          NOT NULL REFERENCES purchase_invoices(id) ON DELETE CASCADE,
    product_id          UUID          NOT NULL REFERENCES products(id),
    quantity            INTEGER       NOT NULL,
    unit_price_usd      NUMERIC(19,4) NOT NULL,
    extra_cost_usd      NUMERIC(19,4) NOT NULL DEFAULT 0,
    landed_unit_cost    NUMERIC(19,6) NOT NULL,
    CONSTRAINT chk_item_qty CHECK (quantity > 0),
    CONSTRAINT chk_item_price CHECK (unit_price_usd > 0),
    CONSTRAINT chk_landed_cost CHECK (landed_unit_cost > 0)
);

CREATE INDEX idx_invoices_store ON purchase_invoices(store_id);
CREATE INDEX idx_invoice_items_invoice ON invoice_items(invoice_id);
CREATE INDEX idx_invoice_items_product ON invoice_items(product_id);

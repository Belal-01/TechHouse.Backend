-- Delivery companies the shop hands parcels to (fees are usually paid in SYP).
CREATE TABLE delivery_companies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    name            VARCHAR(255)  NOT NULL,
    contact_info    TEXT,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    version         INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_delivery_companies_store_name ON delivery_companies(store_id, lower(name));

-- The customer is written on each order (no customer records for now).
ALTER TABLE orders
    ADD COLUMN customer_name        VARCHAR(255),
    ADD COLUMN customer_phone       VARCHAR(50),
    ADD COLUMN customer_address     TEXT,
    ADD COLUMN delivery_company_id  UUID REFERENCES delivery_companies(id),
    ADD CONSTRAINT chk_delivery_cost_currency CHECK (delivery_cost_currency IS NULL OR delivery_cost_currency IN ('USD', 'SYP')),
    ADD CONSTRAINT chk_order_currency CHECK (currency IN ('USD', 'SYP'));

CREATE INDEX idx_orders_store_ordered ON orders(store_id, ordered_at DESC);

-- An expense of an order entered by mistake is voided (kept, flagged), never deleted; the ledger gets a reversal.
ALTER TABLE expenses ADD COLUMN voided_at TIMESTAMPTZ;
ALTER TABLE expenses ADD CONSTRAINT chk_expense_currency CHECK (currency IN ('USD', 'SYP'));

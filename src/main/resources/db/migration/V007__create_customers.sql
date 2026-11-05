CREATE TABLE customers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    name            VARCHAR(255)  NOT NULL,
    phone           VARCHAR(50),
    address         TEXT,
    notes           TEXT,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_customers_store ON customers(store_id) WHERE active = TRUE;

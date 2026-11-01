CREATE TABLE expenses (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id                UUID          NOT NULL REFERENCES stores(id),
    order_id                UUID          REFERENCES orders(id),
    category                VARCHAR(30)   NOT NULL,
    description             TEXT,
    amount                  NUMERIC(19,4) NOT NULL,
    currency                VARCHAR(3)    NOT NULL,
    exchange_rate_id        UUID          REFERENCES exchange_rates(id),
    exchange_rate_snapshot  NUMERIC(19,4),
    amount_usd              NUMERIC(19,4) NOT NULL,
    expense_date            DATE          NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_expense_category CHECK (category IN (
        'ADVERTISING', 'DELIVERY', 'RETURN_SHIPPING', 'PACKAGING', 'OTHER'
    )),
    CONSTRAINT chk_expense_amount CHECK (amount > 0),
    CONSTRAINT chk_expense_usd CHECK (amount_usd > 0)
);

CREATE INDEX idx_expenses_store ON expenses(store_id);
CREATE INDEX idx_expenses_order ON expenses(order_id);
CREATE INDEX idx_expenses_date ON expenses(store_id, expense_date);

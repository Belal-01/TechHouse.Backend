-- The ledger: append-only, signed amounts
CREATE TABLE cash_movements (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id                UUID          NOT NULL REFERENCES stores(id),
    wallet_currency         VARCHAR(3)    NOT NULL,
    movement_type           VARCHAR(30)   NOT NULL,
    amount                  NUMERIC(19,4) NOT NULL,
    exchange_rate_id        UUID          REFERENCES exchange_rates(id),
    exchange_rate_snapshot  NUMERIC(19,4),
    reference_type          VARCHAR(30),
    reference_id            UUID,
    exchange_group          UUID,
    note                    TEXT,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_movement_type CHECK (movement_type IN (
        'CAPITAL_IN', 'SALE_PAYMENT', 'EXCHANGE_IN',
        'REFUND', 'PURCHASE_PAYMENT', 'EXPENSE',
        'WITHDRAWAL_PERSONAL', 'EXCHANGE_OUT', 'ADJUSTMENT'
    )),
    CONSTRAINT chk_movement_sign CHECK (
        (movement_type IN ('CAPITAL_IN', 'SALE_PAYMENT', 'EXCHANGE_IN', 'ADJUSTMENT') AND amount != 0)
        OR
        (movement_type IN ('REFUND', 'PURCHASE_PAYMENT', 'EXPENSE', 'WITHDRAWAL_PERSONAL', 'EXCHANGE_OUT') AND amount != 0)
    )
);

CREATE INDEX idx_cash_store_wallet ON cash_movements(store_id, wallet_currency);
CREATE INDEX idx_cash_reference ON cash_movements(reference_type, reference_id);
CREATE INDEX idx_cash_exchange_group ON cash_movements(exchange_group) WHERE exchange_group IS NOT NULL;
CREATE INDEX idx_cash_created ON cash_movements(store_id, created_at);

-- Prevent UPDATE and DELETE on cash_movements (append-only ledger)
CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'cash_movements is append-only. Use a reversal entry instead.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cash_movements_no_update
    BEFORE UPDATE ON cash_movements
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();

CREATE TRIGGER trg_cash_movements_no_delete
    BEFORE DELETE ON cash_movements
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();

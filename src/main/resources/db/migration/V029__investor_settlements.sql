CREATE TABLE investor_invoice_terms (
    invoice_id UUID NOT NULL REFERENCES purchase_invoices(id) ON DELETE CASCADE,
    investor_id UUID NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    profit_percent NUMERIC(5,2) NOT NULL CHECK (profit_percent BETWEEN 0 AND 100),
    PRIMARY KEY (invoice_id, investor_id)
);

INSERT INTO investor_invoice_terms
SELECT DISTINCT pay.invoice_id, p.id, p.profit_percent
FROM purchase_invoice_payments pay JOIN partners p ON p.id = pay.partner_id
WHERE pay.source = 'INVESTOR';

CREATE FUNCTION snapshot_investor_terms() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO investor_invoice_terms
    SELECT NEW.invoice_id, id, profit_percent FROM partners WHERE id = NEW.partner_id
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_snapshot_investor_terms AFTER INSERT ON purchase_invoice_payments
FOR EACH ROW WHEN (NEW.source = 'INVESTOR') EXECUTE FUNCTION snapshot_investor_terms();

CREATE TABLE investor_settlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    investor_id UUID NOT NULL REFERENCES partners(id),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('CAPITAL_PAYOUT', 'PROFIT_PAYOUT', 'REINVEST')),
    amount_usd NUMERIC(19,4) NOT NULL CHECK (amount_usd > 0),
    account_id UUID REFERENCES cash_accounts(id),
    payment_id UUID UNIQUE REFERENCES purchase_invoice_payments(id) ON DELETE SET NULL,
    reversed_at TIMESTAMPTZ,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((kind = 'REINVEST') = (account_id IS NULL))
);
CREATE INDEX idx_investor_settlements_investor ON investor_settlements(investor_id);

ALTER TABLE cash_movements DROP CONSTRAINT chk_movement_type;
ALTER TABLE cash_movements ADD CONSTRAINT chk_movement_type CHECK (movement_type IN (
    'CAPITAL_IN', 'SALE_PAYMENT', 'EXCHANGE_IN', 'TRANSFER_IN', 'ADVANCE_RETURN',
    'REFUND', 'PURCHASE_PAYMENT', 'EXPENSE', 'WITHDRAWAL_PERSONAL', 'EXCHANGE_OUT', 'TRANSFER_OUT', 'ADVANCE_OUT',
    'ADJUSTMENT', 'CAPITAL_PAYOUT', 'PROFIT_PAYOUT'
));

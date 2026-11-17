CREATE TABLE installation_materials (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id),
    name VARCHAR(255) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
    cost_usd NUMERIC(19,4) NOT NULL CHECK (cost_usd > 0),
    account_id UUID NOT NULL REFERENCES cash_accounts(id),
    purchased_on DATE NOT NULL,
    depleted_at TIMESTAMPTZ
);
CREATE INDEX idx_installation_materials_store ON installation_materials (store_id, purchased_on DESC);

CREATE TABLE charging_installations (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id),
    customer VARCHAR(255) NOT NULL,
    technician VARCHAR(255),
    installed_on DATE NOT NULL,
    received_usd NUMERIC(19,4) NOT NULL CHECK (received_usd > 0),
    materials_usd NUMERIC(19,4) NOT NULL CHECK (materials_usd >= 0),
    technician_usd NUMERIC(19,4) NOT NULL CHECK (technician_usd >= 0),
    receipt_account_id UUID NOT NULL REFERENCES cash_accounts(id),
    technician_account_id UUID REFERENCES cash_accounts(id),
    CHECK (technician_usd = 0 OR technician_account_id IS NOT NULL)
);
CREATE INDEX idx_charging_installations_store ON charging_installations (store_id, installed_on DESC);

ALTER TABLE cash_movements DROP CONSTRAINT chk_movement_type;
ALTER TABLE cash_movements ADD CONSTRAINT chk_movement_type CHECK (movement_type IN (
    'CAPITAL_IN', 'SALE_PAYMENT', 'EXCHANGE_IN', 'TRANSFER_IN', 'ADVANCE_RETURN',
    'REFUND', 'PURCHASE_PAYMENT', 'EXPENSE', 'WITHDRAWAL_PERSONAL', 'EXCHANGE_OUT', 'TRANSFER_OUT', 'ADVANCE_OUT',
    'ADJUSTMENT', 'CAPITAL_PAYOUT', 'PROFIT_PAYOUT',
    'INSTALLATION_MATERIAL_PURCHASE', 'INSTALLATION_RECEIPT', 'INSTALLATION_TECHNICIAN'
));

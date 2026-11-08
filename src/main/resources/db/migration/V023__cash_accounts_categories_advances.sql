-- 1) Cash accounts ("boxes"): each holds a USD and a SYP balance. Every store starts with two.
CREATE TABLE cash_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    name            VARCHAR(100)  NOT NULL,
    is_default      BOOLEAN       NOT NULL DEFAULT FALSE,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    version         INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_cash_accounts_store_name ON cash_accounts(store_id, lower(name));
CREATE UNIQUE INDEX uq_cash_accounts_default ON cash_accounts(store_id) WHERE is_default;

INSERT INTO cash_accounts (store_id, name, is_default) SELECT id, 'في يدي', TRUE FROM stores;
INSERT INTO cash_accounts (store_id, name, is_default) SELECT id, 'شام كاش', FALSE FROM stores;

-- Existing movements happened before accounts existed: they belong to the default box. The ledger is append-only,
-- so the guard is lifted for this one backfill only.
ALTER TABLE cash_movements ADD COLUMN account_id UUID REFERENCES cash_accounts(id);
ALTER TABLE cash_movements DISABLE TRIGGER trg_cash_movements_no_update;
UPDATE cash_movements m SET account_id = a.id
FROM cash_accounts a WHERE a.store_id = m.store_id AND a.is_default;
ALTER TABLE cash_movements ENABLE TRIGGER trg_cash_movements_no_update;
ALTER TABLE cash_movements ALTER COLUMN account_id SET NOT NULL;
CREATE INDEX idx_cash_account ON cash_movements(account_id, wallet_currency);

-- New movement kinds: moving money between boxes, and advances to staff. The old sign check listed every type by
-- name (and only required amount <> 0), so it is replaced by that plain rule.
ALTER TABLE cash_movements DROP CONSTRAINT chk_movement_sign;
ALTER TABLE cash_movements ADD CONSTRAINT chk_movement_nonzero CHECK (amount <> 0);
ALTER TABLE cash_movements DROP CONSTRAINT chk_movement_type;
ALTER TABLE cash_movements ADD CONSTRAINT chk_movement_type CHECK (movement_type IN (
    'CAPITAL_IN', 'SALE_PAYMENT', 'EXCHANGE_IN', 'TRANSFER_IN', 'ADVANCE_RETURN',
    'REFUND', 'PURCHASE_PAYMENT', 'EXPENSE', 'WITHDRAWAL_PERSONAL', 'EXCHANGE_OUT', 'TRANSFER_OUT', 'ADVANCE_OUT',
    'ADJUSTMENT'
));

-- 2) Expense categories the owner manages. system_key marks the ones the app itself uses (DELIVERY for orders).
CREATE TABLE expense_categories (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    name            VARCHAR(100)  NOT NULL,
    system_key      VARCHAR(30),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    version         INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_expense_categories_store_name ON expense_categories(store_id, lower(name));
CREATE UNIQUE INDEX uq_expense_categories_system ON expense_categories(store_id, system_key) WHERE system_key IS NOT NULL;

INSERT INTO expense_categories (store_id, name, system_key)
SELECT s.id, c.name, c.key FROM stores s
CROSS JOIN (VALUES ('إعلانات', 'ADVERTISING'), ('توصيل', 'DELIVERY'), ('إرجاع', 'RETURN_SHIPPING'),
                   ('تغليف', 'PACKAGING'), ('أخرى', 'OTHER')) AS c(name, key);

ALTER TABLE expenses ADD COLUMN category_id UUID REFERENCES expense_categories(id);
UPDATE expenses e SET category_id = c.id FROM expense_categories c
WHERE c.store_id = e.store_id AND c.system_key = e.category;
ALTER TABLE expenses ALTER COLUMN category_id SET NOT NULL;
ALTER TABLE expenses DROP COLUMN category;
CREATE INDEX idx_expenses_category ON expenses(category_id);

-- Which box paid the expense (null when it was paid out of a staff advance).
ALTER TABLE expenses ADD COLUMN account_id UUID REFERENCES cash_accounts(id);
UPDATE expenses e SET account_id = a.id FROM cash_accounts a WHERE a.store_id = e.store_id AND a.is_default;

-- 3) People who may take money from the shop (managers). An advance is money the shop is still owed.
CREATE TABLE staff_people (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    name            VARCHAR(255)  NOT NULL,
    phone           VARCHAR(50),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    version         INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_staff_people_store_name ON staff_people(store_id, lower(name));

-- Signed: + the person owes more (took money), − owes less (returned it, or spent it on the shop).
CREATE TABLE advance_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    person_id       UUID          NOT NULL REFERENCES staff_people(id),
    kind            VARCHAR(20)   NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    account_id      UUID          REFERENCES cash_accounts(id),
    expense_id      UUID          REFERENCES expenses(id),
    note            TEXT,
    created_by      UUID          REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_advance_kind CHECK (kind IN ('WITHDRAWAL', 'RETURN', 'EXPENSE')),
    CONSTRAINT chk_advance_currency CHECK (currency IN ('USD', 'SYP')),
    CONSTRAINT chk_advance_sign CHECK ((kind = 'WITHDRAWAL' AND amount > 0) OR (kind <> 'WITHDRAWAL' AND amount < 0))
);
CREATE INDEX idx_advance_person ON advance_entries(person_id, created_at);

CREATE TRIGGER trg_advance_entries_no_update BEFORE UPDATE ON advance_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();
CREATE TRIGGER trg_advance_entries_no_delete BEFORE DELETE ON advance_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();

-- 4) Shown on invoices and orders: which box paid the invoice, which box received the order's money.
ALTER TABLE purchase_invoices ADD COLUMN account_id UUID REFERENCES cash_accounts(id);
UPDATE purchase_invoices i SET account_id = a.id FROM cash_accounts a WHERE a.store_id = i.store_id AND a.is_default;
ALTER TABLE orders ADD COLUMN payment_account_id UUID REFERENCES cash_accounts(id);
UPDATE orders o SET payment_account_id = a.id FROM cash_accounts a
WHERE a.store_id = o.store_id AND a.is_default AND o.status = 'DELIVERED';

-- 5) Every new store starts with the same two boxes and the default expense categories.
CREATE OR REPLACE FUNCTION seed_store_finance()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO cash_accounts (store_id, name, is_default) VALUES (NEW.id, 'في يدي', TRUE), (NEW.id, 'شام كاش', FALSE);
    INSERT INTO expense_categories (store_id, name, system_key) VALUES
        (NEW.id, 'إعلانات', 'ADVERTISING'), (NEW.id, 'توصيل', 'DELIVERY'), (NEW.id, 'إرجاع', 'RETURN_SHIPPING'),
        (NEW.id, 'تغليف', 'PACKAGING'), (NEW.id, 'أخرى', 'OTHER');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stores_seed_finance AFTER INSERT ON stores
    FOR EACH ROW EXECUTE FUNCTION seed_store_finance();

-- Purchase invoices in two stages (on the way → arrived), with a list of costs and who paid for each part.
-- Existing invoices become "arrived"; their shipping/customs become cost lines and their payment a payment line,
-- with the same amounts, so no cost, balance or profit changes.

-- People who put money into invoices: the company owners and per-invoice investors.
CREATE TABLE partners (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    name            VARCHAR(255)  NOT NULL,
    kind            VARCHAR(20)   NOT NULL,
    -- Owners: their share of the company. Investors: their default share of the profit on what they fund.
    profit_percent  NUMERIC(5,2)  NOT NULL DEFAULT 50,
    phone           VARCHAR(50),
    notes           TEXT,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    version         INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_partner_kind CHECK (kind IN ('OWNER', 'INVESTOR')),
    CONSTRAINT chk_partner_percent CHECK (profit_percent >= 0 AND profit_percent <= 100)
);
CREATE UNIQUE INDEX uq_partners_store_name ON partners(store_id, lower(name));

ALTER TABLE purchase_invoices ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED';
ALTER TABLE purchase_invoices ADD COLUMN received_at TIMESTAMPTZ;
ALTER TABLE purchase_invoices ADD CONSTRAINT chk_invoice_status CHECK (status IN ('IN_TRANSIT', 'RECEIVED'));
UPDATE purchase_invoices SET received_at = created_at;
ALTER TABLE purchase_invoices ALTER COLUMN status DROP DEFAULT;

CREATE TABLE purchase_invoice_costs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id              UUID          NOT NULL REFERENCES purchase_invoices(id) ON DELETE CASCADE,
    kind                    VARCHAR(30)   NOT NULL,
    description             VARCHAR(255),
    amount                  NUMERIC(19,4) NOT NULL,
    currency                VARCHAR(3)    NOT NULL,
    exchange_rate_id        UUID          REFERENCES exchange_rates(id),
    exchange_rate_snapshot  NUMERIC(19,4),
    amount_usd              NUMERIC(19,4) NOT NULL,
    -- Added after the goods arrived: spread over the units still in stock at that moment.
    after_receipt           BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_cost_kind CHECK (kind IN ('CHINA_SHIPPING', 'SEA_FREIGHT', 'CUSTOMS', 'OTHER')),
    CONSTRAINT chk_cost_amount CHECK (amount > 0 AND amount_usd > 0),
    CONSTRAINT chk_cost_currency CHECK (currency IN ('USD', 'SYP')),
    CONSTRAINT chk_cost_rate CHECK (currency = 'USD' OR exchange_rate_snapshot IS NOT NULL)
);
CREATE INDEX idx_invoice_costs_invoice ON purchase_invoice_costs(invoice_id);

-- Who paid: for the goods (cost_id null, always USD) or for one cost (in that cost's currency).
CREATE TABLE purchase_invoice_payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id      UUID          NOT NULL REFERENCES purchase_invoices(id) ON DELETE CASCADE,
    cost_id         UUID          REFERENCES purchase_invoice_costs(id) ON DELETE CASCADE,
    -- ACCOUNT: a store box. OWNER / INVESTOR: that person's own money. EXTERNAL: outside money not yet assigned
    -- to a person (invoices entered before this version as "external capital").
    source          VARCHAR(20)   NOT NULL,
    account_id      UUID          REFERENCES cash_accounts(id),
    partner_id      UUID          REFERENCES partners(id),
    amount          NUMERIC(19,4) NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    amount_usd      NUMERIC(19,4) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_payment_amount CHECK (amount > 0 AND amount_usd > 0),
    CONSTRAINT chk_payment_currency CHECK (currency IN ('USD', 'SYP')),
    CONSTRAINT chk_payment_source CHECK (
        (source = 'ACCOUNT' AND account_id IS NOT NULL AND partner_id IS NULL)
        OR (source IN ('OWNER', 'INVESTOR') AND partner_id IS NOT NULL AND account_id IS NULL)
        OR (source = 'EXTERNAL' AND partner_id IS NULL))
);
CREATE INDEX idx_invoice_payments_invoice ON purchase_invoice_payments(invoice_id);
CREATE INDEX idx_invoice_payments_partner ON purchase_invoice_payments(partner_id) WHERE partner_id IS NOT NULL;

-- Line order on the invoice (costs are spread in this order; the last line takes the rounding remainder).
ALTER TABLE invoice_items ADD COLUMN position INTEGER NOT NULL DEFAULT 0;

-- ---- Existing invoices ----------------------------------------------------------------

UPDATE purchase_invoices i SET account_id = a.id FROM cash_accounts a
WHERE i.account_id IS NULL AND a.store_id = i.store_id AND a.is_default;

INSERT INTO purchase_invoice_costs (invoice_id, kind, description, amount, currency, amount_usd, created_at)
SELECT id, 'OTHER', 'شحن', shipping_cost_usd, 'USD', shipping_cost_usd, created_at
FROM purchase_invoices WHERE shipping_cost_usd > 0;

INSERT INTO purchase_invoice_costs (invoice_id, kind, amount, currency, amount_usd, created_at)
SELECT id, 'CUSTOMS', customs_cost_usd, 'USD', customs_cost_usd, created_at
FROM purchase_invoices WHERE customs_cost_usd > 0;

-- The goods
INSERT INTO purchase_invoice_payments (invoice_id, source, account_id, amount, currency, amount_usd, created_at)
SELECT i.id, CASE WHEN i.paid_from = 'INCOME' THEN 'ACCOUNT' ELSE 'EXTERNAL' END, i.account_id, t.total, 'USD', t.total,
       i.created_at
FROM purchase_invoices i
JOIN (SELECT invoice_id, sum(quantity * unit_price_usd) AS total FROM invoice_items GROUP BY invoice_id) t
  ON t.invoice_id = i.id;

-- Each cost, from the same source as the invoice
INSERT INTO purchase_invoice_payments (invoice_id, cost_id, source, account_id, amount, currency, amount_usd, created_at)
SELECT i.id, c.id, CASE WHEN i.paid_from = 'INCOME' THEN 'ACCOUNT' ELSE 'EXTERNAL' END, i.account_id, c.amount, 'USD',
       c.amount_usd, c.created_at
FROM purchase_invoice_costs c JOIN purchase_invoices i ON i.id = c.invoice_id;

ALTER TABLE purchase_invoices DROP COLUMN shipping_cost_usd;
ALTER TABLE purchase_invoices DROP COLUMN customs_cost_usd;
ALTER TABLE purchase_invoices DROP COLUMN paid_from;
ALTER TABLE purchase_invoices DROP COLUMN account_id;

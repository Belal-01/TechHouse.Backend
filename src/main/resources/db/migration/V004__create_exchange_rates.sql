CREATE TABLE exchange_rates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    rate_date       DATE          NOT NULL,
    currency_pair   VARCHAR(7)    NOT NULL,
    buy_rate        NUMERIC(19,4) NOT NULL,
    sell_rate       NUMERIC(19,4) NOT NULL,
    mid_rate        NUMERIC(19,4) NOT NULL,
    source          VARCHAR(20)   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_rate_date UNIQUE (store_id, rate_date, currency_pair),
    CONSTRAINT chk_rate_source CHECK (source IN ('API', 'MANUAL')),
    CONSTRAINT chk_rate_status CHECK (status IN ('ACTIVE', 'PENDING_REVIEW')),
    CONSTRAINT chk_rate_positive CHECK (buy_rate > 0 AND sell_rate > 0 AND mid_rate > 0),
    CONSTRAINT chk_rate_order CHECK (sell_rate >= buy_rate)
);

CREATE INDEX idx_exchange_rates_lookup ON exchange_rates(store_id, currency_pair, rate_date DESC);

CREATE TABLE api_usage (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    month           DATE          NOT NULL,
    call_count      INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT uq_api_usage_month UNIQUE (store_id, month)
);

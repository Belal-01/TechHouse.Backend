-- A day's API rate is refreshed during the day (and can be replaced manually), so track when it last changed
-- and when the provider itself last updated it.
ALTER TABLE exchange_rates
    ADD COLUMN updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN source_updated_at TIMESTAMPTZ;

-- One API key serves every store, so the monthly quota is counted per provider, not per store.
DROP TABLE api_usage;
CREATE TABLE api_usage (
    provider            VARCHAR(30)  NOT NULL,
    month               DATE         NOT NULL,
    call_count          INTEGER      NOT NULL DEFAULT 0,
    provider_remaining  INTEGER,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, month)
);

UPDATE stores
SET rate_api_config = jsonb_set(rate_api_config, '{provider}', '"sp_today"')
WHERE rate_api_config ->> 'provider' = 'lira_today';

-- A day can hold the approved rate and, beside it, a fetched rate that jumped too far and waits for review.
-- Approving the pending one copies it over the approved row, so orders keep pointing at one rate per day.
ALTER TABLE exchange_rates DROP CONSTRAINT uq_rate_date;
ALTER TABLE exchange_rates ADD CONSTRAINT uq_rate_date_status UNIQUE (store_id, rate_date, currency_pair, status);

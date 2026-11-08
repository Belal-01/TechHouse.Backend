-- A share of net profit shown on the dashboard (e.g. a partner's cut); the owner sets its name and percentage.
ALTER TABLE stores
    ADD COLUMN profit_share_percent NUMERIC(5,2) NOT NULL DEFAULT 25,
    ADD COLUMN profit_share_label   VARCHAR(60)  NOT NULL DEFAULT 'حصة من صافي الربح',
    ADD CONSTRAINT chk_profit_share_percent CHECK (profit_share_percent BETWEEN 0 AND 100);

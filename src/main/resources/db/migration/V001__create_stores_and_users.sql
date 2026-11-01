-- Stores: each store has its own currency, timezone, and exchange rate config
CREATE TABLE stores (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255)  NOT NULL,
    country_code    VARCHAR(2)    NOT NULL,
    base_currency   VARCHAR(3)    NOT NULL DEFAULT 'USD',
    local_currency  VARCHAR(3)    NOT NULL,
    timezone        VARCHAR(50)   NOT NULL,
    rate_api_config JSONB         NOT NULL DEFAULT '{}',
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Users
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255)  NOT NULL,
    display_name    VARCHAR(255)  NOT NULL,
    role            VARCHAR(20)   NOT NULL DEFAULT 'OWNER',
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF'))
);

-- Store membership: which users belong to which stores
CREATE TABLE store_members (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    user_id         UUID          NOT NULL REFERENCES users(id),
    role            VARCHAR(20)   NOT NULL DEFAULT 'OWNER',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_store_member UNIQUE (store_id, user_id),
    CONSTRAINT chk_member_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF'))
);

-- Seed: default store (Syria)
INSERT INTO stores (id, name, country_code, local_currency, timezone, rate_api_config)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'البيت التقني - سوريا',
    'SY',
    'SYP',
    'Asia/Damascus',
    '{"provider": "lira_today", "city": "damascus"}'
);

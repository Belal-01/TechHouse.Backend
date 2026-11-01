CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID          NOT NULL REFERENCES stores(id),
    entity_type     VARCHAR(50)   NOT NULL,
    entity_id       UUID          NOT NULL,
    action          VARCHAR(20)   NOT NULL,
    old_value       JSONB,
    new_value       JSONB,
    user_id         UUID          REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_audit_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'STATUS_CHANGE'))
);

CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_store ON audit_log(store_id, created_at DESC);

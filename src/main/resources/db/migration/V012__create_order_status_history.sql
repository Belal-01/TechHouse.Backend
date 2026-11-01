CREATE TABLE order_status_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID          NOT NULL REFERENCES orders(id),
    from_status     VARCHAR(20),
    to_status       VARCHAR(20)   NOT NULL,
    changed_by      UUID          REFERENCES users(id),
    note            TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_history ON order_status_history(order_id, created_at);

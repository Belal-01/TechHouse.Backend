CREATE TABLE order_item_allocations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_item_id   UUID          NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
    lot_id          UUID          NOT NULL REFERENCES inventory_lots(id),
    quantity        INTEGER       NOT NULL,
    unit_cost       NUMERIC(19,6) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_alloc_qty CHECK (quantity > 0),
    CONSTRAINT chk_alloc_cost CHECK (unit_cost > 0)
);

CREATE INDEX idx_allocations_order_item ON order_item_allocations(order_item_id);
CREATE INDEX idx_allocations_lot ON order_item_allocations(lot_id);

CREATE TABLE inventory_lots (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id            UUID          NOT NULL REFERENCES stores(id),
    product_id          UUID          NOT NULL REFERENCES products(id),
    invoice_item_id     UUID          NOT NULL REFERENCES invoice_items(id),
    qty_in              INTEGER       NOT NULL,
    qty_left            INTEGER       NOT NULL,
    unit_cost           NUMERIC(19,6) NOT NULL,
    received_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_lot_qty_in CHECK (qty_in > 0),
    CONSTRAINT chk_lot_qty_left CHECK (qty_left >= 0),
    CONSTRAINT chk_lot_qty_max CHECK (qty_left <= qty_in),
    CONSTRAINT chk_lot_cost CHECK (unit_cost > 0)
);

-- FIFO index: oldest lots with remaining stock first
CREATE INDEX idx_lots_fifo ON inventory_lots(store_id, product_id, received_at, id)
    WHERE qty_left > 0;

CREATE INDEX idx_lots_product ON inventory_lots(product_id);

-- Sequence for human-readable order numbers
CREATE SEQUENCE order_number_seq START 1;

CREATE TABLE orders (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id                UUID          NOT NULL REFERENCES stores(id),
    customer_id             UUID          REFERENCES customers(id),
    order_number            VARCHAR(30)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    channel                 VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    currency                VARCHAR(3)    NOT NULL,
    exchange_rate_id        UUID          REFERENCES exchange_rates(id),
    exchange_rate_snapshot  NUMERIC(19,4),
    items_total             NUMERIC(19,4) NOT NULL,
    delivery_fee_charged    NUMERIC(19,4) NOT NULL DEFAULT 0,
    delivery_cost           NUMERIC(19,4) NOT NULL DEFAULT 0,
    delivery_cost_currency  VARCHAR(3),
    return_type             VARCHAR(20),
    notes                   TEXT,
    ordered_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    delivered_at            TIMESTAMPTZ,
    returned_at             TIMESTAMPTZ,
    voided_at               TIMESTAMPTZ,
    version                 INTEGER       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_order_number UNIQUE (store_id, order_number),
    CONSTRAINT chk_order_status CHECK (status IN ('PENDING', 'DELIVERED', 'RETURNED', 'VOIDED')),
    CONSTRAINT chk_order_channel CHECK (channel IN ('MANUAL', 'ONLINE')),
    CONSTRAINT chk_return_type CHECK (return_type IS NULL OR return_type IN ('UNDELIVERED', 'POST_DELIVERY')),
    CONSTRAINT chk_items_total CHECK (items_total >= 0),
    CONSTRAINT chk_delivery_fee CHECK (delivery_fee_charged >= 0),
    CONSTRAINT chk_delivery_cost CHECK (delivery_cost >= 0)
);

CREATE TABLE order_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id      UUID          NOT NULL REFERENCES products(id),
    quantity        INTEGER       NOT NULL,
    unit_price      NUMERIC(19,4) NOT NULL,
    CONSTRAINT chk_order_item_qty CHECK (quantity > 0),
    CONSTRAINT chk_order_item_price CHECK (unit_price > 0)
);

CREATE INDEX idx_orders_store_status ON orders(store_id, status);
CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id);

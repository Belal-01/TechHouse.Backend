-- Wallet balances per store per currency
CREATE VIEW v_wallet_balances AS
SELECT
    store_id,
    wallet_currency,
    SUM(amount) AS balance
FROM cash_movements
GROUP BY store_id, wallet_currency;

-- Stock on hand (in warehouse, not allocated to pending orders)
CREATE VIEW v_stock_on_hand AS
SELECT
    l.store_id,
    l.product_id,
    p.name AS product_name,
    SUM(l.qty_left) AS qty_available,
    SUM(l.qty_left * l.unit_cost) AS total_value_usd
FROM inventory_lots l
JOIN products p ON p.id = l.product_id
WHERE l.qty_left > 0
GROUP BY l.store_id, l.product_id, p.name;

-- Stock in transit (allocated to PENDING orders)
CREATE VIEW v_stock_in_transit AS
SELECT
    o.store_id,
    oi.product_id,
    p.name AS product_name,
    SUM(oia.quantity) AS qty_in_transit,
    SUM(oia.quantity * oia.unit_cost) AS total_cost_usd
FROM order_item_allocations oia
JOIN order_items oi ON oi.id = oia.order_item_id
JOIN orders o ON o.id = oi.order_id
JOIN products p ON p.id = oi.product_id
WHERE o.status = 'PENDING'
GROUP BY o.store_id, oi.product_id, p.name;

-- Order P&L (profit per delivered order)
CREATE VIEW v_order_pnl AS
SELECT
    o.id AS order_id,
    o.store_id,
    o.order_number,
    o.delivered_at,
    -- Revenue in USD
    CASE
        WHEN o.currency = 'USD' THEN o.items_total + o.delivery_fee_charged
        ELSE (o.items_total + o.delivery_fee_charged) / NULLIF(o.exchange_rate_snapshot, 0)
    END AS revenue_usd,
    -- COGS
    (SELECT COALESCE(SUM(oia.quantity * oia.unit_cost), 0)
     FROM order_item_allocations oia
     JOIN order_items oi ON oi.id = oia.order_item_id
     WHERE oi.order_id = o.id) AS cogs_usd,
    -- Delivery cost in USD
    CASE
        WHEN o.delivery_cost_currency = 'USD' THEN o.delivery_cost
        ELSE o.delivery_cost / NULLIF(o.exchange_rate_snapshot, 0)
    END AS delivery_cost_usd
FROM orders o
WHERE o.status = 'DELIVERED';

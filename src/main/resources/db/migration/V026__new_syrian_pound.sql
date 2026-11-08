-- The Syrian pound was redenominated: 1 new pound = 100 old pounds (two zeros removed).
-- Every SYP amount and every SYP-per-USD rate already stored is converted to new pounds. USD figures don't change:
-- an SYP amount and its rate are both divided by 100, so amount / rate stays the same.

-- Rates
UPDATE exchange_rates SET buy_rate = buy_rate / 100, sell_rate = sell_rate / 100, mid_rate = mid_rate / 100;

-- Orders: SYP prices and fees; the fixed rate on every order
UPDATE order_items i SET unit_price = i.unit_price / 100
FROM orders o WHERE o.id = i.order_id AND o.currency = 'SYP';
UPDATE orders SET items_total = items_total / 100, delivery_fee_charged = delivery_fee_charged / 100 WHERE currency = 'SYP';
UPDATE orders SET delivery_cost = delivery_cost / 100 WHERE delivery_cost_currency = 'SYP';
UPDATE orders SET exchange_rate_snapshot = exchange_rate_snapshot / 100 WHERE exchange_rate_snapshot IS NOT NULL;

-- Expenses
UPDATE expenses SET amount = amount / 100 WHERE currency = 'SYP';
UPDATE expenses SET exchange_rate_snapshot = exchange_rate_snapshot / 100 WHERE exchange_rate_snapshot IS NOT NULL;

-- The ledger and the advances are append-only; their guards are lifted for this one conversion only.
ALTER TABLE cash_movements DISABLE TRIGGER trg_cash_movements_no_update;
UPDATE cash_movements SET amount = amount / 100 WHERE wallet_currency = 'SYP';
UPDATE cash_movements SET exchange_rate_snapshot = exchange_rate_snapshot / 100 WHERE exchange_rate_snapshot IS NOT NULL;
ALTER TABLE cash_movements ENABLE TRIGGER trg_cash_movements_no_update;

ALTER TABLE advance_entries DISABLE TRIGGER trg_advance_entries_no_update;
UPDATE advance_entries SET amount = amount / 100 WHERE currency = 'SYP';
ALTER TABLE advance_entries ENABLE TRIGGER trg_advance_entries_no_update;

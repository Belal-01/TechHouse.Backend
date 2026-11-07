-- Suppliers use optimistic locking through BaseEntity
ALTER TABLE suppliers ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uq_suppliers_store_name ON suppliers(store_id, lower(name));

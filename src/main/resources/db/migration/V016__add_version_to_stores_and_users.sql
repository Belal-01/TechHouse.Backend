-- BaseEntity uses @Version (optimistic locking); stores and users were created without it
ALTER TABLE stores ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users  ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

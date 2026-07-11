ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN;
UPDATE users SET active = TRUE WHERE active IS NULL;
ALTER TABLE users ALTER COLUMN active SET DEFAULT TRUE;
ALTER TABLE users ALTER COLUMN active SET NOT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS version BIGINT;
UPDATE users SET version = 0 WHERE version IS NULL;
ALTER TABLE users ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE users ALTER COLUMN version SET NOT NULL;

CREATE TABLE IF NOT EXISTS outbox_messages (
    id UUID PRIMARY KEY, aggregate_type VARCHAR(100) NOT NULL, aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL, event_version INTEGER NOT NULL, payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, available_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0, published_at TIMESTAMPTZ, last_error TEXT,
    claimed_by VARCHAR(100), claimed_until TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_auth_outbox_unpublished ON outbox_messages(available_at)
    WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS projection_rebuild_checkpoints (
    projection VARCHAR(100) PRIMARY KEY,
    checkpoint BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

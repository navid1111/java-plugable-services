CREATE TABLE IF NOT EXISTS target_projections (
    target_type VARCHAR(100) NOT NULL,
    target_id VARCHAR(200) NOT NULL,
    owner_username VARCHAR(100),
    aggregate_version BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (target_type, target_id)
);
CREATE INDEX IF NOT EXISTS ix_target_projections_active
    ON target_projections(target_type, target_id) WHERE active;

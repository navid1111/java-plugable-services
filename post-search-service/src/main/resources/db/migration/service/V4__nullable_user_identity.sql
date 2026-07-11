ALTER TABLE search_documents ADD COLUMN IF NOT EXISTS author_user_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS ix_search_documents_author_user_id
    ON search_documents(author_user_id) WHERE author_user_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS target_projections (
    target_type VARCHAR(100) NOT NULL, target_id VARCHAR(200) NOT NULL,
    owner_username VARCHAR(100), aggregate_version BIGINT NOT NULL,
    active BOOLEAN NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(target_type, target_id)
);

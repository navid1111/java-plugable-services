-- Shared target projection now stores stable ownership identity.
ALTER TABLE target_projections
    ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(36);

-- Username remains a display snapshot; author filtering and reconciliation use the UUID.
CREATE INDEX IF NOT EXISTS ix_search_documents_author_user_id
    ON search_documents(author_user_id)
    WHERE author_user_id IS NOT NULL;

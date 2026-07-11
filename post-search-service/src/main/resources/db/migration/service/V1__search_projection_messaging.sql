CREATE TABLE IF NOT EXISTS search_documents (
    id BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(100) NOT NULL,
    target_id VARCHAR(200) NOT NULL,
    author_username VARCHAR(100) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    like_count INTEGER NOT NULL DEFAULT 0,
    indexed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_search_documents_target UNIQUE(target_type, target_id)
);

CREATE TABLE IF NOT EXISTS search_term_entries (
    id BIGSERIAL PRIMARY KEY,
    term VARCHAR(100) NOT NULL,
    document_id BIGINT NOT NULL,
    CONSTRAINT uk_search_terms_document UNIQUE(term, document_id)
);

ALTER TABLE search_documents ADD COLUMN IF NOT EXISTS aggregate_version BIGINT;
UPDATE search_documents SET aggregate_version = 0 WHERE aggregate_version IS NULL;
ALTER TABLE search_documents ALTER COLUMN aggregate_version SET DEFAULT 0;
ALTER TABLE search_documents ALTER COLUMN aggregate_version SET NOT NULL;
ALTER TABLE search_documents ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS inbox_messages (
    consumer VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(255),
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- The shared support package contains both inbox and outbox entities. Search only writes
-- inbox rows, but retaining the complete support schema keeps JPA validation deterministic.
CREATE TABLE IF NOT EXISTS outbox_messages (
    id UUID PRIMARY KEY, aggregate_type VARCHAR(100) NOT NULL, aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL, event_version INTEGER NOT NULL, payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, available_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0, published_at TIMESTAMPTZ, last_error TEXT,
    claimed_by VARCHAR(100), claimed_until TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_search_documents_active
    ON search_documents(created_at DESC, id DESC) WHERE deleted_at IS NULL;

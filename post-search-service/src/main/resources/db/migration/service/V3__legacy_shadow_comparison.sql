CREATE TABLE IF NOT EXISTS legacy_search_documents (
    target_type VARCHAR(100) NOT NULL,
    target_id VARCHAR(200) NOT NULL,
    author_username VARCHAR(100) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (target_type, target_id)
);

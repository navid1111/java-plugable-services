CREATE TABLE IF NOT EXISTS posts (
    id BIGSERIAL PRIMARY KEY,
    author_username VARCHAR(100) NOT NULL,
    content VARCHAR(280) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE posts ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
UPDATE posts SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE posts ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE posts ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE posts ADD COLUMN IF NOT EXISTS version BIGINT;
UPDATE posts SET version = 0 WHERE version IS NULL;
ALTER TABLE posts ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE posts ALTER COLUMN version SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_posts_active_created
    ON posts(created_at DESC, id DESC) WHERE deleted_at IS NULL;


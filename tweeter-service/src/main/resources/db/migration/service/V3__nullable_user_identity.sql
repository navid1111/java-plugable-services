ALTER TABLE posts ADD COLUMN IF NOT EXISTS author_user_id VARCHAR(36);
CREATE TABLE IF NOT EXISTS follows (
    id BIGSERIAL PRIMARY KEY,
    follower_username VARCHAR(100) NOT NULL,
    followee_username VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_follows_follower_followee UNIQUE(follower_username, followee_username)
);
ALTER TABLE follows ADD COLUMN IF NOT EXISTS follower_user_id VARCHAR(36);
ALTER TABLE follows ADD COLUMN IF NOT EXISTS followee_user_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS ix_posts_author_user_id ON posts(author_user_id) WHERE author_user_id IS NOT NULL;

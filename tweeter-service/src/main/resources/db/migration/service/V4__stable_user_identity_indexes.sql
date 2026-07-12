-- Username columns remain display snapshots only. Relational identity is immutable user UUID.
DROP INDEX IF EXISTS idx_posts_author_created_at;
CREATE INDEX IF NOT EXISTS idx_posts_author_user_created_at
    ON posts(author_user_id, created_at DESC, id DESC);

ALTER TABLE follows DROP CONSTRAINT IF EXISTS uk_follows_follower_followee;
CREATE UNIQUE INDEX IF NOT EXISTS uk_follows_follower_followee_user_id
    ON follows(follower_user_id, followee_user_id)
    WHERE follower_user_id IS NOT NULL AND followee_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_follows_follower_user_id ON follows(follower_user_id);
CREATE INDEX IF NOT EXISTS idx_follows_followee_user_id ON follows(followee_user_id);

-- Username remains a display snapshot; immutable user_id owns submissions and idempotency.
ALTER TABLE submissions ADD COLUMN IF NOT EXISTS user_id VARCHAR(36);
DROP INDEX IF EXISTS uq_submission_idempotency;
CREATE UNIQUE INDEX IF NOT EXISTS uq_submission_user_idempotency
    ON submissions(user_id, idempotency_key)
    WHERE user_id IS NOT NULL AND idempotency_key IS NOT NULL;
DROP INDEX IF EXISTS ix_submission_contest_score;
CREATE INDEX IF NOT EXISTS ix_submission_contest_score
    ON submissions(competition_id, status, submitted_at, problem_id, user_id);

ALTER TABLE target_projections ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(36);

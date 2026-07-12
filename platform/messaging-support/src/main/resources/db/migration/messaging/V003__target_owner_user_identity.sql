-- Stable identity for target ownership. owner_username remains a display snapshot only.
ALTER TABLE target_projections
    ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS ix_target_projections_owner_user_id
    ON target_projections(owner_user_id)
    WHERE owner_user_id IS NOT NULL;

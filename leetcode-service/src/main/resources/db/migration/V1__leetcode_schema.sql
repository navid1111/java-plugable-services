CREATE TABLE IF NOT EXISTS problems (
    id varchar(255) PRIMARY KEY, title varchar(255), description text, difficulty varchar(32),
    tags jsonb, code_stubs jsonb, test_cases jsonb
);
CREATE TABLE IF NOT EXISTS competitions (
    id varchar(255) PRIMARY KEY, title varchar(255), start_time timestamptz, end_time timestamptz
);
CREATE TABLE IF NOT EXISTS competition_problems (
    competition_id varchar(255) NOT NULL, problem_id varchar(255) NOT NULL, problem_order integer,
    PRIMARY KEY (competition_id, problem_id)
);
CREATE TABLE IF NOT EXISTS submissions (
    id bigserial PRIMARY KEY, problem_id varchar(255), username varchar(255), code text,
    language varchar(32), status varchar(32), passed_count integer, total_count integer,
    execution_time_ms integer, error_message text, competition_id varchar(255), submitted_at timestamptz,
    updated_at timestamptz, started_at timestamptz, completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0, idempotency_key varchar(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_submission_idempotency
    ON submissions(username, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_submission_contest_score
    ON submissions(competition_id, status, submitted_at, problem_id, username);
CREATE TABLE IF NOT EXISTS outbox_events (
    id uuid PRIMARY KEY, aggregate_id varchar(255) NOT NULL, event_type varchar(255) NOT NULL,
    payload text NOT NULL, occurred_at timestamptz NOT NULL, published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0, last_error text
);
CREATE INDEX IF NOT EXISTS ix_outbox_unpublished ON outbox_events(occurred_at) WHERE published_at IS NULL;
CREATE TABLE IF NOT EXISTS inbox_events (
    consumer varchar(100) NOT NULL, event_id uuid NOT NULL, processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

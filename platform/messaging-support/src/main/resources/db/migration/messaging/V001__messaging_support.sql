-- Shared outbox/inbox schema for platform services.
-- Services add this location to spring.flyway.locations alongside their own migrations.

CREATE TABLE IF NOT EXISTS outbox_messages (
    id             uuid PRIMARY KEY,
    aggregate_type varchar(100) NOT NULL,
    aggregate_id   varchar(255) NOT NULL,
    event_type     varchar(255) NOT NULL,
    event_version  integer NOT NULL,
    payload        text NOT NULL,
    created_at     timestamptz NOT NULL,
    available_at   timestamptz NOT NULL,
    attempts       integer NOT NULL DEFAULT 0,
    published_at   timestamptz,
    last_error     text,
    claimed_by     varchar(100),
    claimed_until  timestamptz
);

-- Drain candidates: unpublished, due, oldest first. Partial index keeps it small.
CREATE INDEX IF NOT EXISTS ix_outbox_unpublished
    ON outbox_messages (available_at)
    WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS inbox_messages (
    consumer     varchar(100) NOT NULL,
    event_id     uuid NOT NULL,
    event_type   varchar(255),
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

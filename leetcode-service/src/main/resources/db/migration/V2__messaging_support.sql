-- Shared messaging-support schema (outbox/inbox/target projections), mirrored so prod
-- `validate` mode has the tables the messaging-support entities map to. Dev `update`
-- mode would also create them, but this keeps Flyway and Hibernate in agreement.

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

CREATE TABLE IF NOT EXISTS target_projections (
    target_type       varchar(100) NOT NULL,
    target_id         varchar(200) NOT NULL,
    owner_username    varchar(100),
    aggregate_version bigint NOT NULL,
    active            boolean NOT NULL,
    updated_at        timestamptz NOT NULL,
    PRIMARY KEY (target_type, target_id)
);

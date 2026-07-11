# Messaging Recovery Runbook

Operator-facing procedures for the event backbone (outbox → RabbitMQ → inbox). Every
step is concrete enough to run during an incident; the [Recovery Drill](#recovery-drill)
at the end lets a new operator rehearse the two most common recoveries end to end.

## Ownership

The producing service owns event correctness and outbox recovery. Each consuming service
owns its inbox, projection, retry queue, and DLQ. RabbitMQ availability is a platform
responsibility.

| Flow | Producer / owner | Work queue → DLQ |
|------|------------------|------------------|
| post lifecycle → search/targets | tweeter-service | `post-search`, `comment-service`, `media-service` (+ `.retry`, `.dlq`) |
| media lifecycle / cleanup | media-service | `media-service` (+ `.retry`, `.dlq`) |
| user identity | auth-service | per-consumer projections |
| booking notifications | booking-service | consumer queues |
| chat reactions | whatsapp-service | consumer queues |
| judge request → result | leetcode-service ↔ judge worker | `leetcode.judge.requested.v1`, `leetcode.judge.completed.v1` (+ `.retry`, `.dlq`) |

Shared facts used throughout:
- Tables (every producer/consumer DB): `outbox_messages`, `inbox_messages`.
- Exchanges: `platform.events.v1` (topic, all events), `platform.events.dlx` (dead letters).
- Topology per consumer `c`: work queue `c`, delayed retry `c.retry`, dead letters `c.dlq`.
- The broker container is `rabbitmq`; management UI on `:15672`. `rabbitmqctl` is always on
  the container PATH. `rabbitmqadmin` (used for message get/publish/purge below) ships with
  the management plugin — if `docker exec rabbitmq rabbitmqadmin --version` fails, fetch it
  once with `docker exec rabbitmq sh -c 'curl -s localhost:15672/cli/rabbitmqadmin > /usr/local/bin/rabbitmqadmin && chmod +x /usr/local/bin/rabbitmqadmin'`, or drive the same
  get/publish/purge actions from the management UI's Queues tab.

## Signals

Watch these Micrometer meters (per `service` tag) — they map directly to the sections below.

| Meter | Symptom it flags | Go to |
|-------|------------------|-------|
| `messaging.outbox.age.seconds`, `messaging.outbox.backlog` | outbox not draining (broker/publish problem) | [Backlog or Broker Outage](#backlog-or-broker-outage) |
| `messaging.publish.failures` | confirms failing/timing out | [Backlog or Broker Outage](#backlog-or-broker-outage) |
| `messaging.dlq.count` | poison/exhausted messages parked | [DLQ Inspection and Redrive](#dlq-inspection-and-redrive) |
| `messaging.consumer.retries` | consumer failing transiently | [DLQ Inspection and Redrive](#dlq-inspection-and-redrive) |
| `messaging.projection.lag.seconds` | projection behind its SLO | [Projection Rebuild](#projection-rebuild-and-reconciliation) |

## Backlog or Broker Outage

Symptom: `outbox.age.seconds` / `outbox.backlog` climbing, or `publish.failures` rising.

1. Confirm producer databases remain writable — committed outbox rows are the recovery
   source and are never lost by a broker outage. Inspect the backlog:
   ```sql
   -- against the producer DB (e.g. leetcode-db)
   SELECT count(*) FILTER (WHERE published_at IS NULL) AS unpublished,
          min(created_at)  FILTER (WHERE published_at IS NULL) AS oldest,
          max(attempts)    AS max_attempts
   FROM outbox_messages;
   -- what is failing, and why (last_error is redacted, safe to read):
   SELECT id, event_type, attempts, last_error
   FROM outbox_messages
   WHERE published_at IS NULL AND attempts > 0
   ORDER BY attempts DESC LIMIT 20;
   ```
2. Check the broker: `docker exec rabbitmq rabbitmqctl status`,
   `rabbitmqctl list_queues name messages messages_unacknowledged`,
   and alarms: `rabbitmqctl list_alarms` (disk/memory). Restore the broker **before**
   scaling publishers. **Never delete unpublished outbox rows.**
3. The relay self-heals: `OutboxRelay` claims due rows (`FOR UPDATE SKIP LOCKED`),
   publishes with publisher confirms, and on failure bumps `attempts` and pushes
   `available_at` out with capped exponential backoff. Once the broker is healthy the
   backlog drains automatically; no manual publish is required.
4. If a row is wedged far in the future after many failures and you have fixed the cause,
   make it due again (this only re-schedules; it never duplicates a confirmed publish):
   ```sql
   UPDATE outbox_messages
   SET available_at = now(), last_error = NULL
   WHERE published_at IS NULL AND id = '<uuid>';
   ```
5. After the backlog drains, run the owner's [reconciliation](#projection-rebuild-and-reconciliation)
   to confirm projections match source aggregates.

## DLQ Inspection and Redrive

Symptom: `messaging.dlq.count > 0`. Messages reach `c.dlq` after exhausting bounded
retries or being classified as permanently unprocessable (unsupported version, invalid
schema, deterministic failure).

1. **Freeze the loop** for a repeating poison message — stop the consumer if it is thrashing:
   `docker compose stop <consumer-service>`.
2. **Inspect without losing the message** (requeues it):
   ```bash
   docker exec rabbitmq rabbitmqadmin get queue=<consumer>.dlq \
     ackmode=ack_requeue_true count=10
   ```
   Record from each: `message_id` (= event ID), the `type` property (= event type/version),
   `producer`, aggregate ID/version, the failure class, and the redacted `last_error`.
3. **Fix root cause first** — deploy the code/schema/config fix. Never edit an authoritative
   payload in place.
4. **Redrive** by republishing to the work queue with the **original `message_id`** so the
   consumer's inbox still deduplicates it (a redrive is at-least-once, not a bypass):
   ```bash
   # Drain one poison message off the DLQ (removes it) and capture its fields:
   docker exec rabbitmq rabbitmqadmin get queue=<consumer>.dlq \
     ackmode=ack_requeue_false count=1
   # Republish straight to the work queue via the default exchange, preserving identity:
   docker exec rabbitmq rabbitmqadmin publish exchange="" routing_key=<consumer> \
     properties='{"message_id":"<eventId>","type":"<eventType>","delivery_mode":2}' \
     payload='<original-json-envelope>'
   ```
   Because the inbox key is `(consumer, event_id)`, a message that had actually committed
   before is a harmless no-op on redrive.
5. **Audit**: log operator, reason, count, start/end time, and outcome. Purge only after a
   deliberate decision to discard: `rabbitmqadmin purge queue name=<consumer>.dlq`.

## Projection Rebuild and Reconciliation

Symptom: `projection.lag.seconds` beyond SLO, or a reconciliation report shows drift.

1. Stop the affected consumer; snapshot its projection DB (`pg_dump`) as a rollback point.
2. Rebuild/reconcile from the **authoritative owner**, never from another service's DB:
   - **Search** (`post-search`): run the authenticated internal export/rebuild from
     tweeter's authoritative posts (checkpointed); it is idempotent and version-guarded.
   - **Comment/media targets**: reconcile local `target_projections` against tweeter's post
     export — `TargetProjectionStore.reconcilePosts(...)` repairs missing/stale rows and
     tombstones orphans.
   - **LeetCode stuck jobs**: `StaleSubmissionReconciler` re-drives submissions left
     non-terminal past the threshold by emitting a fresh judge request; the result inbox +
     terminal-state guard keep the outcome to exactly one terminal result.
3. Resume at the checkpoint; compare row counts, aggregate versions, and sampled content.
4. If unexplained divergence remains, restore the snapshot and escalate to the owner.

## Schema Rollback

Migrations are forward-only (Flyway). Consumers must tolerate additive fields.

1. For a breaking consumer deployment, redeploy the **previous consumer image**; it keeps
   consuming the previous event version (both versions stay registered during migration).
2. Producers must not remove an old event version until every registered consumer has
   migrated (check the event catalog's consumer list).
3. A bad migration is rolled back by restoring the pre-migration DB snapshot and redeploying
   the prior release — do **not** hand-edit `flyway_schema_history`. Take the snapshot as
   part of every migration change window.

## Credential Rotation

1. Create the replacement least-privilege RabbitMQ user/vhost (see the `definitions.json`
   hook in `platform/rabbitmq/`), or the new datasource credential.
2. Deploy consumers/producers with the new credential (env/secret), rolling, not all at once.
3. Verify: `rabbitmqctl list_connections user name`, `rabbitmqctl list_permissions`, and that
   `messaging.publish.failures` and consumer errors stay flat.
4. Revoke the old credential: `rabbitmqctl delete_user <old>` (or rotate the DB password).
5. **Never** place credentials in events, logs, or committed Compose files. `LogRedactor`
   masks password/token/secret/key/code values that reach logs; keep secrets in `.env`.

## Recovery Drill

Rehearse against the local stack; a new operator should complete this unaided in ~15 min.
Uses the LeetCode judge flow because it exercises outbox, confirms, inbox, retry, and DLQ.

**Setup**
```bash
docker compose --profile leetcode up -d   # brings up rabbitmq + leetcode-db + service
```

**Drill A — broker outage does not lose events**
1. `docker compose stop rabbitmq` (simulate the outage).
2. Submit a solution (via the API/smoke script) so a row is written to `outbox_messages`.
3. Confirm the fact is safe, not lost:
   `docker exec leetcode-db psql -U leetcodeuser -d leetcodedb -c \
   "select count(*) from outbox_messages where published_at is null;"` → ≥ 1.
4. `docker compose start rabbitmq`. Within a few relay cycles the count returns to 0 and the
   submission reaches a terminal status. **Pass:** no manual publish, nothing lost.

**Drill B — DLQ inspect and redrive**
1. Publish a deliberately malformed result to force dead-lettering, or stop the consumer and
   let a message exhaust retries into `leetcode.judge.completed.v1.dlq`.
2. Inspect: `docker exec rabbitmq rabbitmqadmin get queue=leetcode.judge.completed.v1.dlq \
   ackmode=ack_requeue_true count=5` — record `message_id` and `type`.
3. After "fixing" the cause, redrive with the [DLQ redrive](#dlq-inspection-and-redrive)
   command, preserving `message_id`.
4. **Pass:** the submission converges to exactly one terminal result (inbox dedup holds),
   and `messaging.dlq.count` returns to 0.

**Teardown:** `docker compose --profile leetcode down`.

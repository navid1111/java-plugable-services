# Feature Specification: Reliable Service Messaging and LeetCode Repair

**Feature:** `011-service-messaging-leetcode-reliability`  
**Status:** Proposed  
**Scope:** Shared asynchronous messaging foundation and `leetcode-service`. `app-builder` is explicitly out of scope.

## Problem Statement

Services currently have no durable message broker or shared event contract. The LeetCode submission endpoint executes untrusted code synchronously in the API process, so request latency, API capacity, and judge capacity are coupled. A process or service restart can lose work, and there is no retry, dead-letter, idempotency, or delivery observability.

The current LeetCode implementation also has correctness and security gaps:

- `POST /leetcode/problems/{id}/submit` blocks while Docker runs and creates no durable `PENDING` job first.
- `JavaRunner` is a stub and always returns `COMPILE_ERROR`.
- timeout kills the Docker CLI process but does not reliably identify and remove the spawned container.
- stdout/stderr are read only after process completion, which can deadlock when pipe buffers fill.
- problem detail serializes the entity and exposes hidden `testCases`.
- competition existence, time window, and problem membership are not checked at submission time.
- leaderboard filtering does not independently enforce the competition window or registered competition problems.
- input validation, status-transition protection, idempotency, tests, and meaningful smoke assertions are missing.

## Goals

1. Introduce one supported, durable message-queue platform for asynchronous communication between services.
2. Make code submission asynchronous and durable: API accepts, persists, and queues; judge workers execute and report results.
3. Guarantee at-least-once delivery with idempotent consumers and safe retries.
4. Repair LeetCode API correctness, judge isolation, competition scoring, and test coverage.
5. Preserve database ownership: only `leetcode-service` reads or writes `leetcode-db`.

## Non-goals

- Changes to `app-builder`.
- Replacing HTTP for client-facing request/response APIs.
- Event-sourcing all domain data.
- A production multi-tenant sandbox platform; the first implementation remains Docker-based for local/demo deployment.
- Migrating every existing service to events in this feature. The foundation and LeetCode flow are delivered first; other services adopt it incrementally.

## Architecture and Ownership

Use RabbitMQ for the current repository scale and Spring AMQP integration. RabbitMQ owns transport only; PostgreSQL remains the source of truth.

Components:

- `leetcode-service` (API and domain owner): validates requests, writes submissions and outbox records atomically, publishes commands, consumes judge results, exposes status and leaderboards.
- `leetcode-judge-worker`: stateless Spring Boot process built from a dedicated worker module/image. It consumes judge commands, runs sandbox containers, and publishes result events. It has no database credentials.
- `rabbitmq`: durable broker with publisher confirms, persistent messages, quorum queues, retry queues, and dead-letter queues.
- transactional outbox publisher inside `leetcode-service`: publishes committed outbox rows and records broker confirmation. This closes the database-write/message-publish gap.

No service may read another service's database. Events carry stable IDs and required snapshots, not JPA entities.

## Messaging Contract

Exchange: `platform.events.v1` (topic, durable).  
LeetCode command routing key: `leetcode.submission.judge.requested.v1`.  
LeetCode result routing key: `leetcode.submission.judge.completed.v1`.

Required envelope:

```json
{
  "eventId": "uuid",
  "eventType": "leetcode.submission.judge.requested",
  "eventVersion": 1,
  "occurredAt": "2026-07-11T12:00:00Z",
  "producer": "leetcode-service",
  "correlationId": "uuid",
  "causationId": "uuid-or-null",
  "traceId": "string-or-null",
  "payload": {}
}
```

Judge request payload contains `submissionId`, `problemId`, normalized `language`, source `code`, immutable test-case snapshot, execution limits, and attempt number. Judge result contains `submissionId`, terminal status, passed/total counts, runtime, bounded diagnostics, worker attempt, and completion time.

Contract rules:

- JSON fields are additive within a version; breaking changes require a new routing-key/event version.
- Messages and queues are durable; publisher confirms are mandatory.
- Delivery is at least once. Consumers deduplicate by `eventId`; submission updates additionally use `submissionId` plus an allowed state transition.
- Consumer acknowledges only after its local work and outgoing result publication are safe.
- Retry transient failures with bounded exponential backoff and jitter; do not retry invalid code or deterministic judge outcomes.
- After the configured maximum attempts, route to a named DLQ and mark the submission `SYSTEM_ERROR` through a failure result or reconciliation job.
- Logs and metrics include `eventId`, `correlationId`, `submissionId`, routing key, retry count, queue depth, age of oldest message, processing duration, and DLQ count. Source code and hidden tests must never be logged.

## User Stories and Acceptance Criteria

### US1 - Durable asynchronous submission (P1)

An authenticated user submits code and receives `202 Accepted` with a persisted submission in `QUEUED` state and a status URL. The request does not wait for code execution.

- The submission and outbox record are committed in one database transaction.
- If RabbitMQ is temporarily unavailable, the accepted job remains in the outbox and is published after recovery.
- `GET /leetcode/submissions/{id}` returns only the owner-visible submission state and result.
- Repeating a request with the same `Idempotency-Key` and user returns the original submission, not a second job.

### US2 - Reliable judging (P1)

A worker consumes the command, moves the observable state through `QUEUED -> RUNNING -> terminal`, and produces exactly one effective terminal outcome despite duplicate delivery.

- Supported languages are Python 3.11, Node 20, and Java 21; all execute real tests.
- Terminal states are `ACCEPTED`, `WRONG_ANSWER`, `COMPILE_ERROR`, `RUNTIME_ERROR`, `TIME_LIMIT_EXCEEDED`, `MEMORY_LIMIT_EXCEEDED`, and `SYSTEM_ERROR`.
- Duplicate commands/results do not rerun a terminal submission or regress its status.
- A worker crash or lost connection causes redelivery and eventual completion or DLQ placement.

### US3 - Safe execution (P1)

Every run has a unique container name/ID and is forcibly removed on completion or timeout. Execution uses no network, read-only root filesystem, a writable size-limited tmpfs, non-root user, dropped capabilities, no-new-privileges, PID/CPU/memory/output limits, and a hard wall-clock timeout.

- stdout and stderr are drained concurrently and capped.
- images are pinned by digest in deployment configuration and pre-pulled by an explicit setup step.
- the worker cannot mount arbitrary host paths. Docker-socket deployment is documented as local-only; production requires a hardened remote runner or sandbox runtime.

### US4 - Correct problem and competition APIs (P1)

- public problem responses never contain hidden test cases.
- page and limit are validated and capped; request fields use bean validation and a language enum.
- a competition submission is accepted only when the competition exists, is active, and contains the problem.
- leaderboard counts only accepted submissions for competition problems within `[startTime, endTime]` and ranks by distinct solved count descending, then contest-relative last accepted solve ascending, then username for deterministic ties.
- competition creation is admin-protected and validates start/end times and problem membership.

### US5 - Operable messaging platform (P2)

- root Compose and the LeetCode standalone example include RabbitMQ health checks, persistent storage, credentials from environment, and broker/worker readiness checks.
- queues, bindings, retry topology, and DLQs are declared by code/config and are reproducible.
- health distinguishes liveness from readiness; readiness fails when required DB/broker dependencies are unavailable.
- alerts/runbook cover growing queue depth, stale messages, repeated retries, DLQ messages, and stuck `QUEUED`/`RUNNING` submissions.

## Data Changes

Extend `submissions` with `status` enum/string constraint, `updated_at`, `started_at`, `completed_at`, `version` for optimistic locking, `idempotency_key`, and bounded diagnostic fields. Add a unique constraint on `(username, idempotency_key)` when the key is present.

Add:

- `outbox_events(id, aggregate_type, aggregate_id, event_type, event_version, payload, occurred_at, published_at, attempts, next_attempt_at, last_error)`.
- `inbox_events(consumer, event_id, processed_at)` with a unique `(consumer, event_id)` key.

Use Flyway migrations; replace `ddl-auto=update` with `validate` outside local development.

## Success Metrics

- submission acceptance p95 under 300 ms when DB is healthy, independent of judge duration.
- no accepted submission is lost across API, broker, or worker restart tests.
- duplicate-delivery tests yield one effective terminal transition and one leaderboard contribution.
- queue-to-start p95 and execution p95 are separately measurable.
- all language correctness/error/timeout/security integration tests pass.
- standalone smoke test asserts `202`, polls to a terminal state, and verifies leaderboard output through Kong.


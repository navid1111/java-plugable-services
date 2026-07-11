# Implementation Plan: Reliable Messaging and LeetCode Repair

**Spec:** [spec.md](spec.md)  
**Delivery strategy:** establish the broker and contracts, make persistence reliable, split judging from HTTP, then repair and prove domain behavior.

## Technical Decisions

1. **RabbitMQ over Kafka:** this workload is a command/work-queue with retries, per-message acknowledgement, and modest repository scale. RabbitMQ is the smaller operational fit. Revisit Kafka only when durable replay and high-volume event streams become primary requirements.
2. **Transactional outbox:** the API never relies on a dual write to PostgreSQL and RabbitMQ. A scheduled publisher claims committed rows using `FOR UPDATE SKIP LOCKED`, publishes with confirms, and marks them published.
3. **At-least-once plus idempotency:** exactly-once transport is not assumed. Inbox records and guarded submission transitions make duplicates harmless.
4. **Separate worker deployment:** Docker execution, concurrency, and privileges are removed from the web API. Worker concurrency and queue prefetch are configurable and bounded.
5. **Contract-first JSON:** shared envelope/event DTOs and JSON Schemas live in a small `messaging-contracts` module; consumers tolerate additive fields.
6. **Flyway-managed schema:** explicit migrations make retry, outbox, inbox, and submission-state behavior reproducible.

## Delivery Phases

### Phase 0 - Baseline and safety net

- Capture failing/working behavior with controller, repository, runner, and standalone tests.
- Add an architecture decision record for RabbitMQ/outbox and document the local-only Docker socket risk.
- Define the submission state machine and error taxonomy before changing endpoints.

**Exit:** baseline test report exists and old synchronous behavior is covered sufficiently to refactor.

### Phase 1 - Broker and shared contracts

- Add RabbitMQ to root Compose and the standalone example with durable volume, health check, environment credentials, and management UI bound only for local use.
- Add Spring AMQP dependencies/configuration and declare exchange, judge queue, result queue, retry queues, and DLQs.
- Create versioned envelope and judge request/result schemas plus serialization/compatibility tests.

**Exit:** a test publisher/consumer round-trip survives broker restart and topology is automatically recreated.

### Phase 2 - Database reliability

- Introduce Flyway and baseline the existing schema.
- Add submission lifecycle/idempotency columns and outbox/inbox tables with indexes and constraints.
- Implement atomic submission creation plus outbox insertion.
- Implement confirmed outbox publishing, retry scheduling, and stale-outbox metrics.

**Exit:** broker-down integration test accepts a submission, then publishes it after broker recovery without data loss.

### Phase 3 - Judge worker extraction

- Create the independently deployable worker and move runner selection/execution into it.
- Implement bounded concurrency/prefetch and graceful shutdown.
- Publish `RUNNING`/terminal results safely; consume results in the API with inbox deduplication and optimistic state transitions.
- Remove Docker CLI/socket access from `leetcode-service`; grant it only to the local worker deployment.

**Exit:** API restart, worker crash, duplicate message, and broker reconnect tests all converge to one terminal submission state.

### Phase 4 - Runner correctness and hardening

- Replace shell command construction with argument lists and unique named containers.
- Drain capped stdout/stderr concurrently; reliably stop/remove containers in `finally`.
- Apply non-root, read-only, tmpfs, network/capability/PID/CPU/memory limits and digest-pinned images.
- implement genuine Java compile/run harness and normalize Python/JavaScript harness interfaces.
- Separate compile, runtime, timeout, memory, infrastructure, and wrong-answer results.

**Exit:** correct, wrong, compile-error, runtime-error, timeout, output-flood, and escape-attempt cases pass for all three languages; no orphan container remains.

### Phase 5 - API and competition repair

- Return DTOs, never entities; hide test cases and source from unauthorized responses.
- Change submit to `202` and add owner/admin submission status endpoint.
- Add validation, pagination caps, authorization rules, exception mapping, and idempotency-key behavior.
- Validate active competition and problem membership on submission.
- replace in-memory leaderboard pagination with a pageable database query that enforces contest window/membership and deterministic ordering.

**Exit:** REST/repository tests prove security boundaries, status codes, validation, and leaderboard rules.

### Phase 6 - Observability and end-to-end proof

- Add structured correlation/event/submission logging with sensitive-data redaction.
- Add queue/outbox/inbox/judge latency, retries, failures, DLQ, and stuck-submission metrics.
- Add liveness/readiness endpoints and a reconciliation job/runbook for stale work and DLQs.
- Rewrite plug and standalone smoke scripts to assert responses, poll async results, and test broker/worker recovery.

**Exit:** the LeetCode standalone demo is green through Kong and a documented recovery drill passes.

### Phase 7 - Incremental adoption by other services

- Inventory actual cross-service workflows and assign each event a single owning producer.
- Adopt the common envelope/outbox/inbox library one workflow at a time.
- Keep synchronous HTTP for immediate queries; use events for facts and commands that tolerate asynchronous completion.

**Exit:** each migrated workflow has an owner, versioned contract, retry/DLQ policy, idempotency test, dashboard, and runbook.

## Rollout and Compatibility

- Deploy broker/topology first, then schema/API outbox, then worker, then switch submission endpoint to async.
- During a short transition, keep the old endpoint behavior behind a disabled-by-default feature flag only for rollback; never execute both paths for one submission.
- Drain old work before removing the synchronous runner and Docker socket from the API.
- Rollback preserves queued/outbox records; do not roll back schema destructively.

## Principal Risks and Mitigations

- **Duplicate execution:** inbox deduplication, terminal-state guard, unique event IDs, bounded side effects.
- **Poison messages:** schema validation, non-retryable classification, bounded retry, DLQ and redrive tooling.
- **Broker outage:** transactional outbox, readiness reporting, backlog alerting, recovery tests.
- **Docker daemon privilege:** worker isolation and local-only socket; production runner boundary documented before deployment.
- **Hidden-data leakage:** explicit public/internal DTO separation and serialization tests.
- **Queue overload:** bounded publisher/worker rates, prefetch/concurrency limits, admission/rate limits, queue-age alerts.


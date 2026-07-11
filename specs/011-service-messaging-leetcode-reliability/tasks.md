# Tasks: Reliable Messaging and LeetCode Repair

Tasks are ordered by dependency. `[P]` means safe to perform in parallel after its prerequisites.

## Phase 0 - Baseline

- [ ] T001 Add unit tests for current problem list/detail, submit, and competition endpoints; record known failures.
- [ ] T002 [P] Add repository tests for leaderboard window, membership, duplicate solves, and deterministic ties.
- [ ] T003 [P] Add runner characterization tests for Python, JavaScript, and Java, including timeout and large output.
- [ ] T004 Write ADR: RabbitMQ, transactional outbox, at-least-once delivery, and worker boundary.
- [ ] T005 Define and test the allowed submission state-transition table.

## Phase 1 - Messaging platform and contracts

- [ ] T006 Add RabbitMQ service, durable volume, health check, environment-based credentials, and network configuration to root Compose.
- [ ] T007 [P] Add RabbitMQ and worker services to `leetcode-service/plug/compose.plug.yml` and the standalone Compose example.
- [ ] T008 Create `messaging-contracts` module with `EventEnvelope<T>`, judge request/result DTOs, JSON Schemas, and version constants.
- [ ] T009 Add schema/serialization/backward-compatibility tests and payload-size limits.
- [ ] T010 Configure durable topic exchange, quorum work/result queues, retry queues with TTL/dead-letter routing, and terminal DLQs.
- [ ] T011 Add publisher confirms/returns, mandatory routing, connection recovery, and broker TLS/credential production configuration hooks.
- [ ] T012 Add broker topology integration test using a real RabbitMQ container.

## Phase 2 - Persistence and reliable publication

- [ ] T013 Add Flyway; create a baseline migration matching current LeetCode tables and set Hibernate to validate in non-local profiles.
- [ ] T014 Add migration for submission lifecycle timestamps, optimistic version, idempotency key/constraint, and status constraint.
- [ ] T015 Add indexed `outbox_events` and `inbox_events` tables and retention fields.
- [ ] T016 Implement transactional `SubmissionService.createQueuedSubmission(...)` that writes `Submission` and judge-request outbox event atomically.
- [ ] T017 Implement concurrent-safe outbox claiming, confirmed publish, exponential retry/jitter, error truncation, and retention cleanup.
- [ ] T018 Add API idempotency-key handling and concurrency tests for duplicate requests.
- [ ] T019 Prove broker outage/recovery: committed jobs remain queryable and publish exactly effectively once after recovery.

## Phase 3 - Judge worker

- [ ] T020 Create independently runnable `leetcode-judge-worker` module/image with no LeetCode DB credentials.
- [ ] T021 Move language runners behind worker `JudgeService`; add configurable concurrency, prefetch, and graceful shutdown.
- [ ] T022 Implement judge-command consumer validation, inbox/deduplication strategy, retry classification, and acknowledgements.
- [ ] T023 Publish `RUNNING` and terminal result events with confirms; define behavior when result publication is interrupted.
- [ ] T024 Implement result consumer in `leetcode-service` using inbox deduplication and optimistic, monotonic state transitions.
- [ ] T025 Remove runner dependencies, Docker CLI, root execution, and Docker socket from the API service image/Compose entry.
- [ ] T026 Add crash/redelivery, duplicate-command, duplicate-result, reconnect, and graceful-shutdown integration tests.

## Phase 4 - Runner repair and sandbox hardening

- [ ] T027 Replace `sh -c` command concatenation with explicit Docker CLI arguments and a validated image/language allowlist.
- [ ] T028 Assign a unique container name; on timeout/error always stop and remove it, then assert no orphan remains.
- [ ] T029 Drain stdout/stderr concurrently with byte caps and return `OUTPUT_LIMIT_EXCEEDED` or mapped system status.
- [ ] T030 Apply `--network none`, read-only root, size-limited tmpfs, non-root user, `cap-drop=ALL`, `no-new-privileges`, PID, CPU, memory, and wall-time limits.
- [ ] T031 Pin Python/Node/Java runner images by digest and add explicit image pre-pull/readiness documentation.
- [ ] T032 Implement a real Java 21 compile-and-test harness; distinguish compiler diagnostics from runtime errors.
- [ ] T033 Normalize the problem runner contract so function/method names and input binding are explicit metadata, not guessed from reflection or hard-coded names.
- [ ] T034 Escape/transport source and test data without template-string/source injection; cap code/test/message sizes.
- [ ] T035 Add per-language matrices for accepted, wrong answer, compile error, runtime error, timeout, memory/output pressure, malicious output, and network/filesystem attempts.

## Phase 5 - API, security, and competitions

- [ ] T036 Introduce request/public-detail/list/submission-result DTOs; ensure hidden tests and other users' source never serialize.
- [ ] T037 Change submit endpoint to return `202 Accepted`, submission ID/status, and `Location`; add authenticated owner/admin `GET /leetcode/submissions/{id}`.
- [ ] T038 Add bean validation for code, language, page/limit, IDs, and request sizes plus consistent RFC 9457 problem responses.
- [ ] T039 Enforce admin authorization for competition mutation and owner/admin authorization for submission details.
- [ ] T040 Validate competition existence, active window, and problem membership in the same submission transaction.
- [ ] T041 Replace leaderboard query with DB pagination and filters for accepted status, competition window, and competition-problem membership; add deterministic tie ordering.
- [ ] T042 Add indexes supporting status lookup, contest scoring, outbox polling, and stale-submission reconciliation; verify query plans.
- [ ] T043 Add controller/security/repository tests for all validation, authorization, privacy, and competition rules.

## Phase 6 - Operations and proof

- [ ] T044 Add structured logs with event/correlation/submission IDs and tests ensuring code, tokens, and hidden tests are redacted.
- [ ] T045 Add Micrometer metrics for publish confirms/failures, outbox age, queue processing, retries, terminal outcomes, DLQs, and judge duration.
- [ ] T046 Separate liveness/readiness and include DB/broker/worker dependency checks appropriate to each process.
- [ ] T047 Implement stale `QUEUED`/`RUNNING` reconciliation and safe DLQ inspection/redrive commands with audit logging.
- [ ] T048 Write runbooks and alert thresholds for broker outage, backlog/oldest age, DLQ growth, worker saturation, and orphan containers.
- [ ] T049 Rewrite LeetCode plug smoke test to assert HTTP codes/bodies, poll terminal status with a deadline, and verify leaderboard ranking.
- [ ] T050 Add end-to-end recovery test: stop broker, accept job, restart broker; kill worker mid-job; verify one effective completion and no orphan container.
- [ ] T051 Update LeetCode architecture, README, and local setup documentation to describe async behavior and the worker security boundary.

## Phase 7 - Other service adoption (separate follow-up slices)

- [ ] T052 Inventory cross-service workflows and document producer ownership, consumers, consistency expectations, and whether HTTP or events fit each one.
- [ ] T053 For each selected workflow, define a versioned contract, outbox publication, inbox/idempotent consumption, retry/DLQ policy, tests, metrics, and runbook.
- [ ] T054 Do not migrate a workflow until its owner and source of truth are explicit; do not share databases through consumers.

## Definition of Done

- [ ] All P1 acceptance criteria in the spec are automated and green.
- [ ] No Docker socket or code execution exists in the API container.
- [ ] Broker/API/worker restart and duplicate-delivery tests demonstrate no lost accepted submissions and no duplicate leaderboard credit.
- [ ] Hidden tests and source privacy have explicit serialization/authorization tests.
- [ ] Compose, plug kit, Kong path, and standalone smoke are reproducible from a clean checkout.
- [ ] Operational dashboards/runbooks cover backlog, retries, DLQs, stale jobs, and sandbox cleanup.

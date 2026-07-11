# Tasks: Professional Service Communication

**Architecture:** [professional-service-communication.md](../../docs/architecture/professional-service-communication.md)  
**Status:** In progress  
**Rule:** A task is complete only when its stated verification passes. `[P]` means it can run in parallel after its prerequisites.

## Phase 1 — Shared messaging foundation

- [x] T001 Record ADR for HTTP commands/queries, RabbitMQ facts/jobs, and BFF composition. **Verify:** architecture review checklist names owner and consistency model for every flow.
- [x] T002 Create a Maven reactor or independently versioned `messaging-contracts` module containing the standard envelope and event metadata without domain/JPA dependencies. **Verify:** both producer and consumer services compile against it.
- [x] T003 Add JSON Schemas for envelope and initial post/target/media/user events. **Verify:** valid fixtures pass and breaking fixtures fail schema tests.
- [x] T004 Create `messaging-support` with outbox and inbox entities/migrations, sensitive-data-safe serialization, and retention configuration. **Verify:** reusable component test runs against PostgreSQL.
- [x] T005 Implement safe concurrent outbox claiming, mandatory routing, persistent delivery, publisher confirms, exponential retry/jitter, and confirmed-only completion. **Verify:** two publisher instances never effectively publish one claimed row concurrently and broker recovery drains backlog.
- [x] T006 Implement inbox deduplication and atomic consumer transaction helper. **Verify:** duplicate delivery produces one effective database change.
- [x] T007 Declare durable topic exchange, per-consumer quorum queues, bounded retry queues, and DLQs. **Verify:** topology recreates after broker data reset.
- [x] T008 Add unsupported-version, invalid-schema, deterministic, and transient error classification. **Verify:** each class reaches the expected ack/retry/DLQ outcome.
- [x] T009 Propagate `traceparent`, correlation, causation, event, aggregate, and user IDs through HTTP and AMQP. **Verify:** one E2E trace joins producer, broker, and consumer spans.
- [x] T010 Add standard Micrometer metrics and structured logging with code/token/password redaction tests. **Verify:** metrics expose outbox age, publish failures, processing latency, retries, projection lag, and DLQ count.
- [x] T011 Move RabbitMQ to a platform Compose definition with health check, durable volume, per-service credentials hooks, and TLS production hooks. **Verify:** all opted-in profiles reuse one broker without duplicated service definitions.
- [x] T012 Create the event catalog with producer owner, schema/version, consumers, ordering, retention, PII classification, SLO, and runbook link. **Verify:** CI rejects an unregistered event type.

## Phase 2 — Posts to search

- [x] T013 Add post aggregate version and explicit created/updated/deleted lifecycle fields. **Verify:** migration works on both clean and existing post databases.
- [x] T014 Write `post.created.v1` to the tweeter outbox in the same transaction as post creation. **Verify:** forced rollback stores neither row; committed post always has an outbox row.
- [x] T015 Add post update and delete commands with `post.updated.v1` and `post.deleted.v1`. **Verify:** optimistic concurrency prevents lost updates and each committed version emits one event.
- [x] T016 Emit follow created/deleted events idempotently. **Verify:** repeated follow/unfollow commands produce correct state without duplicate effective events.
- [x] T017 Add post-search inbox and event consumer for post created/updated/deleted. **Verify:** projection and token index update atomically.
- [x] T018 Enforce per-post aggregate version in search. **Verify:** duplicate and out-of-order events cannot regress a document.
- [x] T019 Add search projection delete/tombstone behavior and retention policy. **Verify:** deleted posts disappear from queries within the defined SLO.
- [x] T020 Add an authenticated internal post export/rebuild process with checkpointing. **Verify:** empty search DB rebuilds to match authoritative posts.
- [ ] T021 Run event ingestion in shadow comparison mode against legacy client-written documents. **Verify:** mismatch report is empty or explicitly reconciled.
- [ ] T022 Remove public search mutation and like-count endpoints plus client-side indexing after compatibility window. **Verify:** ordinary JWT clients receive 404/403 while search queries remain green.

## Phase 3 — Governed target lifecycle

- [ ] T023 Define registered target types and owner mapping; initially `post -> tweeter-service`. **Verify:** unknown target types are rejected.
- [ ] T024 Add target projection tables/inbox consumers to comment-service and media-service from post lifecycle events. **Verify:** post create/delete produces matching target state idempotently.
- [ ] T025 Require active target existence before comment creation. **Verify:** missing/deleted targets are rejected and existing targets succeed.
- [ ] T026 Require active target existence and visibility/owner authorization before media attachment. **Verify:** cross-user and missing-target uploads are rejected.
- [ ] T027 Define post deletion policy for comments and implement tombstone/delete processing. **Verify:** deletion is eventually reflected and replay is harmless.
- [ ] T028 Define post deletion policy for media and enqueue durable external-object cleanup. **Verify:** metadata and Cloudinary cleanup converge after transient failure.
- [ ] T029 Add target reconciliation comparing local projections to authoritative post export. **Verify:** injected missing/stale targets are repaired.
- [ ] T030 Add comment/media count events or BFF summaries without cross-database reads. **Verify:** post detail counts converge and duplicate events do not inflate them.

## Phase 4 — Media lifecycle and identity

- [ ] T031 Introduce media upload intents with expiry, owner, target, size/type limits, and idempotency key. **Verify:** abandoned intents expire safely.
- [ ] T032 Use signed direct-upload URLs where supported and finalize verified metadata. **Verify:** forged object IDs, MIME types, and oversized uploads fail.
- [ ] T033 Publish media uploaded/processed/failed/deleted events transactionally. **Verify:** consumers observe one effective lifecycle per media ID.
- [ ] T034 Implement durable media delete worker for Cloudinary with retry/DLQ and reconciliation. **Verify:** provider outage followed by recovery removes the object exactly effectively once.
- [ ] T035 Add immutable UUID/ULID `userId` to auth and place it in JWT `sub`; retain username as display claim. **Verify:** login/me contracts expose stable ID and username.
- [ ] T036 Add user registered/profile-updated/deactivated events without credentials or tokens. **Verify:** schema and log tests prove sensitive fields never leave auth.
- [ ] T037 Add nullable `userId` columns to service tables and dual-write during migration. **Verify:** new records contain stable ID while old tokens remain temporarily compatible.
- [ ] T038 Backfill username references using an audited auth export and switch reads/contracts to user ID. **Verify:** row counts and unresolved-reference report pass.
- [ ] T039 Remove username as relational identity after compatibility period. **Verify:** username rename requires no cross-service database updates.

## Phase 5 — Workload security, BFF, and remaining events

- [ ] T040 Add service-to-service identity using mTLS or short-lived workload JWTs with audience/scope. **Verify:** direct unauthenticated internal calls fail.
- [ ] T041 Verify user JWT signatures in every service as defense in depth and standardize role/scope authorization. **Verify:** forged tokens and admin escalation fail even when bypassing Kong.
- [ ] T042 Create a BFF/API-composer service behind Kong with strict deadlines, bounded parallelism, tracing, and RFC 9457 errors. **Verify:** dependency failure follows documented partial-response policy.
- [ ] T043 Implement composed post-detail endpoint with post, author projection, media, comment summary, and visibility checks. **Verify:** one client call returns the documented contract without DB sharing.
- [ ] T044 Implement composed feed endpoint or event-built feed detail projection based on load test results. **Verify:** latency SLO and freshness SLO pass.
- [ ] T045 Add booking created/cancelled/availability events while keeping slot locking local. **Verify:** concurrent no-double-booking tests remain green and notifications are idempotent.
- [ ] T046 Add chat message-created/read events for external reactions only; retain DB/WebSocket delivery source of truth. **Verify:** broker outage does not break message persistence or later event delivery.
- [ ] T047 Align LeetCode commands/results with shared contracts/support, persistent inbox, retry/DLQ, confirmed results, and stale-job reconciliation. **Verify:** crash/redelivery test converges to one terminal result.

## Phase 6 — Tests, operations, and cutover

- [ ] T048 Add producer/consumer contract compatibility checks to CI. **Verify:** incompatible schema change fails the build.
- [ ] T049 Add Testcontainers component suites for every outbox/inbox flow. **Verify:** tests use real PostgreSQL and RabbitMQ.
- [ ] T050 Add broker-down, publisher-confirm-timeout, consumer-crash-before/after-commit, poison-message, duplicate, and out-of-order tests. **Verify:** no committed fact is lost and no side effect is duplicated.
- [ ] T051 Add E2E post create/update/delete test asserting search, comment target, media target, and BFF convergence. **Verify:** test passes from clean volumes.
- [ ] T052 Add dashboards and alerts for outbox age, queue age/depth, projection lag, retries, DLQs, circuit breakers, and BFF partial responses. **Verify:** synthetic failure triggers expected alert.
- [ ] T053 Write owner-specific runbooks for outage, replay, reconciliation, DLQ inspection/redrive, schema rollback, and credential rotation. **Verify:** another operator completes a recovery drill.
- [ ] T054 Remove legacy client orchestration and deprecated public mutation routes. **Verify:** repository search finds no client-side search/media lifecycle dual writes.
- [ ] T055 Run migration/backfill against a production-like data copy and document timing, locks, rollback, and unresolved records. **Verify:** zero unexplained loss and rollback drill passes.
- [ ] T056 Final integration demo: auth → create post → event-indexed search → attach media/comment → composed view → delete → eventual cleanup, plus booking/chat/LeetCode regression. **Verify:** one-command demo and all service test suites pass.

## Definition of Done

- [ ] No client performs required cross-service propagation.
- [ ] Every distributed fact has one owner, versioned contract, outbox, idempotent consumer, retry/DLQ policy, metrics, and runbook.
- [ ] Services never read another service's database.
- [ ] Search is derived only from authoritative events.
- [ ] Comments and media reject missing, deleted, or unauthorized targets.
- [ ] Workloads authenticate internally and user tokens are verified in depth.
- [ ] Failure and replay tests prove no lost committed work and no duplicate effective side effects.

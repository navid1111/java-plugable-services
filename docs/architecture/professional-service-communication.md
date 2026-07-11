# Professional Service Communication Architecture

**Status:** Proposed  
**Applies to:** auth, tweeter/posts, media, comments, post-search, WhatsApp/chat, booking, and LeetCode services  
**Out of scope:** app-builder internals and rewriting every service in one release

## Executive Summary

The services are separated into different applications and databases, but the system does not yet have a reliable integration architecture. Most workflows are coordinated by the browser rather than by backend-owned contracts. For example, a client can create a post, manually call search indexing, upload media against an arbitrary `targetType/targetId`, and create comments against the same unverified reference.

This makes the services look independent while leaving consistency, retries, ordering, authorization, and cleanup to every client. A professional design should use:

- synchronous HTTP only for immediate commands and queries;
- RabbitMQ domain events for state propagation and background reactions;
- a transactional outbox in every event-producing service;
- idempotent inbox/projection handling in every consumer;
- an API composition/BFF layer for assembled screens;
- versioned contracts, stable identifiers, service identity, tracing, metrics, retries, and DLQs;
- explicit ownership and lifecycle rules for every entity and projection.

RabbitMQ, first introduced for the LeetCode judge flow, should become shared platform infrastructure rather than LeetCode-specific plumbing.

## What Exists Today

### Service ownership

The repository already has a good starting boundary:

| Service | Owns |
|---|---|
| auth-service | users, credentials, token issuance |
| tweeter-service | posts, follows, feed queries |
| media-service | uploaded asset metadata and Cloudinary lifecycle |
| comment-service | comments attached to generic targets |
| post-search-service | search documents and inverted-index data |
| whatsapp-service | chats, participants, messages, delivery inbox |
| booking-service | resources, slots, bookings |
| leetcode-service | problems, submissions, competitions, leaderboard data |

Each service mostly owns a separate PostgreSQL database. This is correct and should be preserved.

### Current integration pattern

The effective integration layer is the public client:

1. A browser calls `POST /posts`.
2. The browser reads the returned post ID.
3. The browser calls `PUT /post-search/documents/post/{id}`.
4. It may separately call `POST /media/targets/post/{id}`.
5. It may call `POST /comments/targets/post/{id}`.
6. A screen performs multiple independent calls and assembles the result locally.

The backend services do not know whether the related target exists, whether it was later deleted, or whether all dependent writes succeeded.

## Problems Found

### 1. The browser performs distributed transactions

Post creation and search indexing are separate client calls. If the post succeeds but indexing fails, the post exists but cannot be found. Retrying can create duplicate side effects depending on the endpoint. Closing the browser permanently abandons recovery.

The same applies to upload-and-attach, create-and-comment, delete-and-clean-up, and like-count propagation.

**Professional rule:** clients may initiate a business operation, but they must not be responsible for propagating the resulting state across services.

### 2. Generic target references are unverified

Media, comments, and search accept strings such as `targetType=post` and `targetId=123`. Syntactic validation exists, but the owning post may not exist. This allows:

- comments and media for nonexistent targets;
- attachments to targets the caller cannot access;
- stale records after target deletion;
- collisions caused by inconsistent target naming;
- enumeration and authorization gaps.

Cross-service foreign keys are not appropriate, but ungoverned strings are also insufficient.

### 3. Search is writable from public clients

The search service accepts author, content, creation time, and like counts from request bodies. A client can submit a projection that disagrees with the source post. Search should be a derived read model maintained from authoritative post events, not another public source of truth.

### 4. Deletes have no lifecycle propagation

Deleting a post does not reliably remove or tombstone its search document, comments, and media. Deleting media metadata and deleting the external Cloudinary object also form a distributed operation without a durable recovery workflow.

### 5. Authentication is confused with service trust

Several services locally decode a bearer token to obtain a username. Decoding a JWT payload is not signature verification. Kong validation helps at the public edge, but internal network access or routing mistakes can bypass that assumption.

User identity and calling-service identity are different concerns:

- user identity says who initiated the operation;
- service identity says which trusted workload made the internal call.

Both require defined trust boundaries.

### 6. Identity references use mutable usernames

Services store usernames as identity references. Renaming a user would require coordinated updates everywhere and historical data could become ambiguous. A stable immutable `userId` should be the cross-service identifier; username should be display data.

### 7. There is no shared event contract

The LeetCode messaging work defines its own envelope and queues, but other services have no common:

- event naming/version policy;
- correlation and causation IDs;
- serialization schema;
- retry classification;
- idempotency convention;
- DLQ/redrive policy;
- compatibility test;
- ownership registry.

### 8. Reliability semantics are incomplete

Without transactional outboxes, a service can commit database state and fail before publishing its event. Without inbox deduplication, redelivery can repeat side effects. Without bounded retries and DLQs, poison messages can block work or retry forever.

The initial LeetCode outbox also needs further hardening: confirmed publication, safe concurrent row claiming, retry scheduling, inbox persistence, and result idempotency should become part of the shared implementation.

### 9. No consistent observability across a workflow

A request that becomes a post, a search document, a notification, and a media attachment cannot currently be traced as one workflow. Logs lack a universally propagated correlation ID, and there are no standard metrics for outbox age, queue depth, event processing latency, retries, or DLQs.

### 10. API composition and event propagation are mixed up

Two separate problems need separate tools:

- **Data propagation:** search and notification services reacting when a post changes. Use events.
- **Screen composition:** returning a post with author, media, comments, and counts. Use a BFF/API composer or purpose-built read model.

Using asynchronous events for every query creates stale copies everywhere. Using synchronous HTTP for all propagation creates tightly coupled failure chains. The architecture must distinguish the two.

## Target Architecture

## Communication Decision Matrix

| Need | Mechanism | Example |
|---|---|---|
| Immediate user command | HTTP through Kong/BFF | create a post, book a slot |
| Immediate authoritative query | HTTP | get a post, get my bookings |
| Propagate a committed fact | RabbitMQ domain event | `post.created.v1` |
| Background job with one logical worker | RabbitMQ command queue | `submission.judge.requested.v1` |
| Assemble a page from several services | BFF/API composer or read model | YouTube post detail page |
| Bulk analytics/replay at very high scale | Future Kafka evaluation | not required now |

### Platform components

1. **RabbitMQ cluster**
   - durable topic exchanges;
   - quorum queues for important workflows;
   - publisher confirms and mandatory routing;
   - per-consumer retry queues;
   - terminal DLQs;
   - TLS and non-default per-service credentials outside local development.

2. **Shared messaging-contracts library**
   - event envelope and metadata;
   - JSON serialization rules;
   - schema/version compatibility tests;
   - event type constants only, with no shared domain entities.

3. **Shared messaging-support library**
   - outbox/inbox persistence helpers;
   - publisher confirmation and retry behavior;
   - listener error classification;
   - correlation propagation;
   - standard metrics and structured logging.

4. **API composer/BFF**
   - client-specific aggregation endpoints;
   - bounded parallel calls with deadlines;
   - partial-response policy where appropriate;
   - no ownership of authoritative domain data;
   - no direct access to service databases.

The BFF can initially be a small Spring Boot service behind Kong. It should not be embedded in static clients and should not become a new monolith containing domain rules.

## Standard Event Envelope

```json
{
  "eventId": "uuid",
  "eventType": "post.created",
  "eventVersion": 1,
  "occurredAt": "2026-07-11T12:00:00Z",
  "producer": "tweeter-service",
  "aggregateType": "post",
  "aggregateId": "01J...",
  "correlationId": "uuid",
  "causationId": "uuid-or-null",
  "traceparent": "00-...",
  "payload": {}
}
```

Rules:

- event types describe facts in past tense;
- events are immutable;
- breaking payload changes require a new version;
- additive optional fields are permitted within a version;
- timestamps are UTC ISO-8601;
- IDs are strings at contract boundaries;
- no JPA entity is serialized;
- events include only data consumers legitimately need;
- secrets, access tokens, full binary data, and unnecessary personal data are prohibited.

## Delivery and Consistency Model

The system should explicitly promise **at-least-once delivery and idempotent effects**, not exactly-once delivery.

### Producer transaction

In one local database transaction, a producer:

1. changes its owned aggregate;
2. inserts a complete outbox event.

An outbox relay later publishes the event with RabbitMQ publisher confirms. Only confirmed messages are marked published.

### Consumer transaction

A consumer:

1. validates the envelope and supported version;
2. attempts to insert `(consumerName, eventId)` into its inbox;
3. exits successfully if it was already processed;
4. applies its local projection/side effect;
5. commits the inbox marker and local change together;
6. acknowledges the message only after commit.

### Failure policy

- validation and unsupported-version failures go directly to DLQ;
- deterministic domain rejections are not retried;
- transient database, network, and dependency failures use exponential backoff with jitter;
- retries are bounded;
- DLQ redrive is an audited operational action;
- consumers must tolerate delayed and duplicate events;
- ordering is guaranteed only per aggregate where required, using aggregate version checks.

## Proposed Domain Events

### Auth

- `user.registered.v1`: immutable `userId`, initial username, occurred time.
- `user.profile-updated.v1`: optional display projection updates.
- `user.deactivated.v1`: disables access and triggers consumer-specific handling.

Passwords, password hashes, and tokens never leave auth-service.

### Posts/Tweeter

- `post.created.v1`
- `post.updated.v1`
- `post.deleted.v1`
- `post.like-count-changed.v1`
- `follow.created.v1`
- `follow.deleted.v1`

Post events contain the stable post ID, author user ID, searchable content snapshot, visibility, aggregate version, and relevant timestamps.

### Comments

- `comment.created.v1`
- `comment.deleted.v1`
- `comment.count-changed.v1`

Comment creation remains an HTTP command, but authorization is based on a trusted target registry/projection or a synchronous authorization check with a strict timeout. On `post.deleted`, comments are tombstoned or deleted according to an explicit retention policy.

### Media

- `media.uploaded.v1`
- `media.processing-completed.v1`
- `media.processing-failed.v1`
- `media.deleted.v1`

Upload should use an explicit lifecycle: create upload intent, upload directly to object storage using a signed URL where possible, finalize metadata, then publish the event. External-object deletion uses a durable cleanup job and is retried independently from the client request.

### Search

Search becomes event-driven and read-only to ordinary clients:

- consumes `post.created`, `post.updated`, `post.deleted`, and `post.like-count-changed`;
- maintains its own projection idempotently;
- exposes query endpoints only;
- supports a rebuild command or replay/import process from the post owner.

Public endpoints that directly write arbitrary search documents should be removed after migration.

### Chat

Chat messages already use a database inbox for delivery, which is a useful local pattern. RabbitMQ should be used for external reactions such as push notifications, moderation, or analytics—not as a replacement for the chat database source of truth or WebSocket delivery semantics.

Potential events:

- `chat.message-created.v1`
- `chat.message-read.v1`

### Booking

- `booking.created.v1`
- `booking.cancelled.v1`
- `slot.availability-changed.v1`

Slot locking and no-double-booking stay in the booking database transaction. Events notify other services only after the authoritative booking commits.

### LeetCode

Retain judge commands/results, but align them with the shared envelope/support library. Add durable inbox deduplication, retry/DLQ topology, confirmed result publication, and stale-submission reconciliation.

## Target Reference Integrity

Comments and media are reusable cross-domain capabilities, so they cannot use database foreign keys to every target type. Use a governed target contract:

```text
targetType: registered value such as "post"
targetId: stable owner-issued ID
targetOwner: "tweeter-service"
targetVersion: optional aggregate version
```

Each supported target type has one owner and declared lifecycle events. Consumers maintain a lightweight local target projection from `target.created`, visibility-changed, and `target.deleted` events, or consume owner-specific events such as `post.created`.

This projection answers:

- does the target exist?
- is it active/deleted?
- who owns it?
- what visibility policy applies?

For operations requiring current authorization, the service may make a synchronous owner check, but it must use a short timeout, circuit breaker, bulkhead, and deny safely when certainty is required.

## Synchronous HTTP Standards

Events do not eliminate HTTP. Internal HTTP calls must follow consistent rules:

- explicit OpenAPI contracts and generated/typed clients;
- connect, request, and total deadlines;
- retries only for safe/idempotent operations;
- request idempotency keys for retriable commands;
- exponential backoff and jitter;
- circuit breakers and concurrency bulkheads;
- propagated W3C `traceparent` and correlation ID;
- RFC 9457 problem responses;
- service-to-service authentication using mTLS or short-lived workload tokens;
- user context forwarded as signed, minimal claims rather than a blindly trusted username header.

Do not call services through their public Kong route from inside the platform unless policy enforcement specifically requires it. Use a protected internal route/service identity and keep the public edge separate.

## Identity and Authorization

1. Introduce immutable `userId` in auth-service.
2. Put `userId` in JWT `sub`; keep username as a display claim.
3. Store `userId` in new domain records and events.
4. Migrate existing username references with a compatibility period.
5. Validate JWT signatures in services as defense in depth, or use a trusted identity-aware internal proxy with a clearly enforced network boundary.
6. Add roles/scopes for administrative operations.
7. Distinguish end-user authorization from workload authentication.

## API Composition

A YouTube-like or social screen should not perform an uncontrolled waterfall of calls. Provide BFF endpoints such as:

```text
GET /ui/posts/{postId}
GET /ui/feed?cursor=...
```

The composer may fetch posts, media, comment summaries, and author projections in parallel. It applies deadlines and returns a defined partial response if noncritical components are unavailable.

For high-read endpoints, build a dedicated materialized view from events rather than performing many synchronous calls on every request. The choice is based on freshness requirements:

| Requirement | Choice |
|---|---|
| authoritative and immediately current | synchronous owner query |
| fast feed with acceptable small delay | event-built read model |
| optional secondary panel | parallel BFF call with partial fallback |

## Observability and Operations

Every service must emit:

- structured JSON logs containing service, trace ID, correlation ID, event ID, aggregate ID, and outcome;
- HTTP server/client latency and error metrics;
- outbox unpublished count and oldest age;
- queue ready/unacked depth and oldest-message age;
- consumer processing duration, retry count, failure classification, and DLQ count;
- projection lag from `occurredAt` to processed time;
- health endpoints that separate liveness from readiness.

Required alerts:

- oldest outbox event exceeds threshold;
- queue age grows continuously;
- DLQ receives any critical event;
- consumer retry rate spikes;
- projection lag violates its freshness objective;
- circuit breaker remains open;
- BFF dependency failure causes excessive partial responses.

Each event flow needs a runbook covering ownership, safe replay, DLQ inspection/redrive, duplicate handling, schema compatibility, and reconciliation.

## Testing Strategy

1. **Contract tests:** JSON Schema/OpenAPI compatibility in producer and consumer builds.
2. **Component tests:** real PostgreSQL and RabbitMQ with Testcontainers.
3. **Idempotency tests:** deliver every event twice and assert one effective outcome.
4. **Failure tests:** broker down, consumer crash before/after commit, publisher-confirm timeout, poison event, dependency timeout.
5. **Reconciliation tests:** intentionally miss an event and prove the projection can be repaired.
6. **End-to-end tests:** create/update/delete a post and assert eventual search/media/comment lifecycle behavior.
7. **Security tests:** forged JWT, missing service identity, unauthorized target, cross-user access, sensitive-data leakage.
8. **Performance tests:** queue backlog recovery, BFF fan-out capacity, connection-pool and consumer saturation.

## Migration Plan

### Phase 1 - Foundation

- Make RabbitMQ a platform-level Compose service and production deployment dependency.
- Extract the LeetCode envelope into `messaging-contracts` and reusable support modules.
- Implement production-grade outbox/inbox, publisher confirms, retry queues, DLQs, metrics, and tracing.
- Establish an event catalog with owner, schema, consumers, retention, ordering, and data classification.

### Phase 2 - Posts to search

This is the highest-value first migration because the browser currently performs the dual write.

1. Tweeter writes `post.created/updated/deleted` to its outbox.
2. Search consumes events into its projection with an inbox.
3. Run event indexing in shadow mode and compare it with existing documents.
4. Backfill/rebuild existing posts.
5. Stop client-side search indexing.
6. Remove public search mutation endpoints after a compatibility window.

### Phase 3 - Target lifecycle for comments and media

- Register supported target types and their owning services.
- Build local target projections from post lifecycle events.
- reject new writes to missing/deleted targets.
- consume deletion/visibility events.
- add reconciliation for existing dangling records.

### Phase 4 - Media workflow

- Introduce upload intents and finalization.
- move external cleanup to durable jobs.
- publish media lifecycle events.
- expose media summaries to the BFF/read model.

### Phase 5 - Identity migration

- add immutable auth `userId` and JWT migration.
- dual-read/dual-write user ID and username temporarily.
- backfill service tables.
- switch contracts and queries to user ID.
- remove username as a relational identity.

### Phase 6 - API composition

- introduce BFF endpoints for social/YouTube screens.
- move client waterfalls into bounded parallel backend composition.
- create materialized feed/detail read models where latency or scale justifies them.

### Phase 7 - Remaining event flows

- booking notifications and availability projections;
- chat push/moderation/analytics events;
- standardized LeetCode retry, inbox, DLQ, and reconciliation behavior.

## Concrete Task Backlog

- [ ] A001 Create ADR for HTTP versus RabbitMQ versus BFF decisions.
- [ ] A002 Create `messaging-contracts` module with envelope, schema rules, and compatibility tests.
- [ ] A003 Create `messaging-support` module with outbox/inbox migrations and APIs.
- [ ] A004 Add confirmed publisher with safe concurrent claiming, backoff, and retention.
- [ ] A005 Add consumer deduplication, error classification, retry topology, and DLQ tooling.
- [ ] A006 Add OpenTelemetry propagation and Micrometer messaging metrics.
- [ ] A007 Create the event catalog and assign an owner to every event.
- [ ] A008 Add post aggregate version and post outbox events.
- [ ] A009 Convert search to consume post events idempotently.
- [ ] A010 Backfill search and compare old/new projections.
- [ ] A011 remove browser calls that write search documents or like counts.
- [ ] A012 Define registered target types and lifecycle/authorization contracts.
- [ ] A013 Add post target projections to media and comment services.
- [ ] A014 Add post-delete cleanup/tombstone policies and reconciliation.
- [ ] A015 Introduce durable media upload/finalize/delete workflows.
- [ ] A016 Add immutable `userId`, update JWT claims, and plan table backfills.
- [ ] A017 Add service-to-service workload authentication.
- [ ] A018 Create BFF service and first composed post-detail endpoint.
- [ ] A019 Add contract, duplicate-delivery, crash, and broker-outage tests.
- [ ] A020 Add dashboards, alerts, DLQ runbooks, and reconciliation commands.

## Definition of Done

The communication architecture is professional when:

- no browser performs required cross-service propagation;
- every distributed state change has one authoritative owner;
- important published facts use transactional outbox delivery;
- every consumer is idempotent and has bounded retry/DLQ behavior;
- search is a derived read model, not client-authored data;
- comments/media cannot attach to invalid or unauthorized targets;
- deletes and visibility changes propagate through defined lifecycle contracts;
- internal calls authenticate workloads and propagate traces;
- clients use a BFF or documented composition strategy rather than uncontrolled waterfalls;
- broker/consumer outages do not lose committed work;
- dashboards and runbooks make backlog, lag, retries, and reconciliation operable;
- all contracts are versioned and compatibility-tested.

## Recommended First Implementation Slice

Start with **posts to search**. It removes the clearest client-side dual write and establishes the reusable pattern without changing the post creation API:

```text
Client -> POST /posts
              |
              +-- one DB transaction: post + outbox(post.created.v1)
                                      |
                                      v
                                  RabbitMQ
                                      |
                                      v
                         post-search inbox + projection
```

Once this flow survives duplicate delivery, broker outage, consumer crash, and replay tests, reuse the same platform for post deletion, media/comment target lifecycle, booking notifications, chat side effects, and the remaining LeetCode reliability work.

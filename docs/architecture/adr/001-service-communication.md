# ADR 001: Service Communication Mechanisms

**Status:** Accepted  
**Date:** 2026-07-11

## Context

The repository currently uses public HTTP APIs and client-side orchestration for workflows that cross posts, search, comments, and media. LeetCode introduced RabbitMQ for background judging, but no platform-wide decision defines when HTTP, messaging, or API composition applies.

## Decision

Use the following rules:

1. Use synchronous HTTP for an immediate command result or authoritative query.
2. Use RabbitMQ domain events to propagate facts already committed by an owning service.
3. Use RabbitMQ command queues for asynchronous work performed by one logical worker group.
4. Use a BFF/API composer for client-shaped reads spanning multiple owners.
5. Never use a browser to perform required state propagation between services.
6. Never share service databases.
7. Promise at-least-once delivery with idempotent effects, not exactly-once transport.
8. Every event-producing transaction uses an outbox; every state-changing consumer uses an inbox.

## Flow Classification

| Flow | Owner | Mechanism | Consistency |
|---|---|---|---|
| Register/login/user lookup | auth | HTTP | immediate |
| Create/update/delete post | tweeter | HTTP command | immediate owner commit |
| Reflect posts in search | tweeter → search | domain events | eventual, measurable lag |
| Validate comment/media target | post owner projection; synchronous fallback only when required | events + optional bounded HTTP | deny safely when uncertain |
| Assemble post detail/feed | BFF | bounded parallel HTTP or event-built read model | documented per field |
| Upload/finalize media | media | HTTP intent/finalize + background cleanup | immediate metadata, eventual provider cleanup |
| Deliver chat over WebSocket | chat | DB + WebSocket | immediate/local replay |
| External chat reactions | chat → consumers | domain events | eventual |
| Reserve booking slot | booking | local DB transaction | immediate/serializable invariant |
| Booking notifications | booking → consumers | domain events | eventual |
| Execute submitted code | LeetCode API → judge worker | command/result queues | asynchronous terminal state |

## Consequences

- Producers and consumers gain operational complexity: schemas, broker topology, outbox/inbox tables, retries, DLQs, metrics, and runbooks.
- Services remain independently deployable and database-owned.
- Clients become simpler and can no longer create conflicting projections.
- Eventual consistency is explicit and measured rather than accidental.
- A BFF is permitted for composition but cannot own domain rules or access service databases.

## Rejected Alternatives

- **All synchronous HTTP:** creates failure chains and cannot atomically propagate committed state.
- **All asynchronous messaging:** inappropriate for immediate authoritative queries and complicates user interaction.
- **Shared database:** destroys service ownership and deployment independence.
- **Kafka now:** replay streams are not the primary workload; RabbitMQ fits commands, work queues, routing, and current scale.
- **Client orchestration:** has no durable recovery or trustworthy authorization boundary.

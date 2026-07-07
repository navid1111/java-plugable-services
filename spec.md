# Pluggable Services Platform behind Kong — Product Spec

> Spec-Kit layout: this file is the product-level vision and index.
> Binding rules live in [.specify/memory/constitution.md](.specify/memory/constitution.md).
> Each feature has its own `spec.md` / `plan.md` / `tasks.md` under [specs/](specs/).

## Vision

A collection of **independent, reusable backend services** (auth,
posts/"tweeter", chat/"whatsapp", turf booking) behind a single **Kong API
gateway**. Any subset composes into a product with minimal code change —
and every service must *prove* it by being plugged into a separate host
project before it counts as done (Constitution Art. VII).

| Composition | Product |
|---|---|
| tweeter | a Twitter-like app |
| chat | a messaging app |
| tweeter + chat | a Facebook-like app |
| turf + chat + tweeter | a booking site with posts and messaging |

Composition happens **at the gateway and frontend layer**, never by services
calling into each other's code or databases.

## Architecture

```
                         Kong (:18000)
                           │  jwt verification + rate limiting at the edge
         ┌────────────┬────┴────────┬───────────────┐
      /auth         /posts        /chat          /bookings
   auth-service  tweeter-svc   whatsapp-svc     turf-svc
         │            │             │                │
      users-db     posts-db      chats-db       bookings-db
```

Every service ships a **plug kit** (`<service>/plug/`: compose fragment +
idempotent Kong setup script + smoke test) — the unit of reuse. A host
project mounts a service by composing its image and running its plug script;
`examples/<service>-standalone/` demonstrates exactly that for each service.

Interview-scale versions of these systems (and the upgrade path we are
deliberately not taking yet) are documented in
[docs/architecture/](docs/architecture/).

## Current state (built & verified)

- Kong 3.9 (DB-backed, Postgres) proxying on host **:18000**, Admin API :8001
- Spring Boot 4.1 (Java 21) auth service with `/auth/register`,
  `/auth/login`, and `/auth/me` (BCrypt + Postgres)
- Edge auth: HS256 JWT (`iss=springboot-auth`, `sub=<username>`); Kong `jwt`
  plugin verifies on protected routes; rate limiting 10/min, 100/hour
- `tweeter-service` with `/posts` CRUD, follow/unfollow, reverse-chron feed
  with cursor paging, and a standalone plug-kit demo
- Everything in Docker Compose; multi-stage Dockerfile (no local JDK)

## Feature index

| # | Feature | Delivers | Status |
|---|---------|----------|--------|
| [001](specs/001-auth-service/spec.md) | auth-service | register/login/me, monorepo restructure, first plug kit + standalone demo | Done |
| [002](specs/002-tweeter-service/spec.md) | tweeter-service | posts, follow graph, cursor feed + standalone demo | Done |
| [003](specs/003-whatsapp-service/spec.md) | whatsapp-service | chat REST + WebSocket realtime + offline inbox + standalone demo | Draft |
| [004](specs/004-turf-service/spec.md) | turf-service | venues/slots/bookings, no double-booking + standalone demo | Draft |
| [005](specs/005-composition-facebook/spec.md) | composition demo | tweeter + chat = "facebook", one command, one login, zero code change | Draft |
| [006](specs/006-hardening/spec.md) | hardening | .env secrets, HTTPS, RS256 evaluation, decK | Draft |

## Out of scope (platform-wide)

- Media upload, audio/video calling, message encryption at rest
- Event bus between services (add when a real cross-service reaction exists)
- Multi-node chat (Redis pub/sub), feed precomputation, celebrity fan-out
- Kubernetes; Compose is the deployment unit

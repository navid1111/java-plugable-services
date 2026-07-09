# Implementation Plan: comment-service

**Branch:** `007-comment-service` | **Date:** 2026-07-09
**Spec:** [spec.md](spec.md)

## Summary

New Spring Boot service for reusable comments that attach to arbitrary
external targets by reference. A target can be a tweeter post today, a
YouTube video later, or any other resource a host product names. It follows
the established platform shape: own DB, one Kong prefix, jwt at the edge,
plug kit, and standalone demo. Unlike the live-comment material in the
prompt attachment, this service is deliberately REST-only and right-sized:
persist comments, page comments, delete own comments.

## Technical Context

- **Language/runtime:** Java 21, Spring Boot 4.1, same scaffold pattern as
  the existing Spring Boot resource services
- **Storage:** PostgreSQL `comments-db`; indexes
  `comments(target_type, target_id, created_at DESC, id DESC)` and
  `comments(author_username, created_at DESC, id DESC)`
- **Identity:** JWT-decode helper copied from existing services; reads
  `sub` only after Kong verifies jwt
- **Gateway:** `/comments` route + `jwt` plugin + rate limiting via plug kit
- **Compose profile:** `comments`
- **Dependency posture:** service stores `targetType` + `targetId` by
  reference; no direct calls to target services and no reads from their DBs

## Constitution Check

| Article | Status |
|---------|--------|
| I - one DB per service | Pass: `comments-db` only |
| II - auth at the edge | Pass: Kong jwt on `/comments`; service reads `sub` |
| III - identity by reference | Pass: stores usernames only, no profile copies |
| IV - plug kit | Pass: `comment-service/plug/` |
| V - no service-to-service calls | Pass: no synchronous target lookup in v1 |
| VI - single ownership | Pass: comment data lives only in comment-service |
| VII - integration demo | Pass: `examples/comments-standalone/` smoke is green |
| VIII - right-sized | Pass: REST + Postgres only; no live broadcast, queue, or cache |

## Design Decisions

1. **Prefix is `/comments`, not nested under a product route.** This keeps
   the service inside one Kong-owned prefix and makes it reusable for
   `tweeter.post`, `youtube.video`, or any future target namespace.
2. **Target key = `targetType` + `targetId`.** `targetType` is a namespaced
   string like `tweeter.post` or `youtube.video`; `targetId` is the owning
   service's id represented as a string. This avoids assuming every service
   uses numeric ids or the same entity names.
3. **No target existence check in v1.** Calling target services from
   comment-service would violate the current platform rule against casual
   service-to-service calls. The host UI is responsible for passing real
   target keys. Strict referential integrity is deferred to a future
   explicit event-backed target reference model if needed.
4. **Newest-first cursor paging.** This matches the repo's existing feed and
   chat history style. Cursor uses `createdAt` plus id so equal timestamps
   are stable.
5. **Hard delete for owner delete.** The first version has no replies,
   moderation, or audit trail, so hard delete keeps reads simple. If replies
   or moderation arrive later, switch to tombstones before adding nesting.
6. **No comment counts yet.** Counts would create either a read-time
   aggregate or cross-service target metadata. Defer until a UI explicitly
   needs it.

## Risks

- **Orphan comments:** Because target existence is not enforced internally,
  comments can technically reference a missing target. Accepted in v1; solve
  later with explicit target-created/target-deleted events or a local target
  reference table if the product needs strict enforcement.
- **Target namespace discipline:** Host products must choose stable
  `targetType` strings (`tweeter.post`, `youtube.video`) and not rename them
  casually, because they are part of the comment key.
- **Route wording:** `/comments/targets/{targetType}/{targetId}` is less
  domain-specific than `/posts/{id}/comments`, but it avoids making this
  reusable service own another service's prefix.
- **JWT helper drift:** Same accepted duplication as tweeter/chat/turf;
  revisit only if helper behavior diverges.

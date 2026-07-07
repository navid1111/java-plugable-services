# Implementation Plan: tweeter-service

**Branch:** `002-tweeter-service` | **Date:** 2026-07-07
**Spec:** [spec.md](spec.md)

## Summary

Second Spring Boot service, first to follow the contract established by
001: own DB, own prefix, jwt at the edge, plug kit, standalone demo. Feed is
deliberately naive fan-out-on-read (see
[docs/architecture/fb-news-feed.md](../../docs/architecture/fb-news-feed.md)
§8 for the scale-up path we are *not* taking yet).

## Technical Context

- **Language/runtime:** Java 21, Spring Boot 4.1 (copy auth-service scaffold)
- **Storage:** PostgreSQL `posts-db`; indexes
  `posts(author_username, created_at DESC)`, `follows(follower_username)`
- **Identity:** JWT-decode helper copied from auth-service (reads `sub`
  only) — accepted duplication, no shared library yet (plan risk register)
- **Gateway:** `/posts` route + `jwt` plugin + rate limiting via plug kit
- **Compose profile:** `tweeter`

## Constitution Check

| Article | Status |
|---------|--------|
| I — one DB per service | ✅ posts-db |
| II — auth at the edge | ✅ jwt plugin on `/posts`; service only decodes `sub` |
| III — identity by reference | ✅ stores usernames, no profile copies; no existence check on followees |
| IV — plug kit | ✅ `tweeter-service/plug/` |
| V — no service-to-service calls | ✅ none (deliberately no auth-service lookup) |
| VI — single ownership | ✅ follow graph lives here and only here |
| VII — integration demo | ✅ `examples/tweeter-standalone/` (composes auth kit + tweeter kit) |
| VIII — right-sized | ✅ fan-out on read; no feed table, no queue, no cache |

## Design Decisions

1. **Feed query shape:** one SQL query — posts where author in (select
   followees) and `(created_at, id) < (cursor_ts, cursor_id)` ordered by
   `created_at DESC, id DESC` limit `pageSize`. Composite cursor solves the
   identical-timestamp edge case declared in the spec.
2. **Follow endpoints under `/posts/users/...`:** keeps the whole service
   inside one Kong prefix (Art. IV) even though "users" reads oddly — the
   alternative (second route) doubles gateway surface for no gain.
3. **Idempotent follow:** `INSERT ... ON CONFLICT DO NOTHING` on the unique
   (follower, followee) pair; DELETE is naturally idempotent.
4. **No pagination on author listing initially** — same cursor mechanism can
   be added later without an API break (timestamp cursor contract).

## Risks

- **JWT helper drift** across services: accepted for 2–3 services; revisit a
  shared module only if behavior actually diverges.
- **Kong route precedence**: `/posts/users/{u}/follow` and `/posts/{id}` are
  both under `/posts` so Kong sees one route; path disambiguation is
  entirely Spring's problem — keep controller mappings unambiguous.

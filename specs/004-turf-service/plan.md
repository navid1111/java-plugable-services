# Implementation Plan: turf-service (booking)

**Branch:** `004-turf-service` | **Date:** 2026-07-07
**Spec:** [spec.md](spec.md)

## Summary

Pure CRUD repeat of the 002 pattern; the only novel element is the
concurrency-safe booking constraint. Everything else is scaffold reuse —
which is the point (SC-004 measures it).

## Technical Context

- **Language/runtime:** Java 21, Spring Boot 4.1 (copy 002 scaffold)
- **Storage:** PostgreSQL `bookings-db`
- **Gateway:** `/bookings` route + jwt + rate limiting via plug kit
- **Compose profile:** `turf`

## Constitution Check

| Article | Status |
|---------|--------|
| I — one DB per service | ✅ bookings-db |
| II — auth at the edge | ✅ jwt on `/bookings`; `sub` only |
| III — identity by reference | ✅ bookings store username |
| IV — plug kit | ✅ `turf-service/plug/` |
| V — no service-to-service calls | ✅ none |
| VI — single ownership | ✅ venues/slots/bookings live here |
| VII — integration demo | ✅ `examples/turf-standalone/` |
| VIII — right-sized | ✅ no Redis locks, no reservation TTLs, no search index |

## Design Decisions

1. **Constraint, not lock.** Partial unique index
   `CREATE UNIQUE INDEX ... ON booking(slot_id) WHERE status = 'active'` —
   the insert either succeeds or violates; map the violation to `409`.
   This is the right-sized stand-in for Ticketmaster's entire Redis-TTL
   apparatus (architecture doc §7): no multi-minute checkout exists here,
   so no temporary reservation state is needed.
2. **Cancel = status flip**, never row delete — keeps history and makes the
   partial index do the slot-freeing automatically.
3. **Slots are pre-seeded rows**, not computed intervals — dodges overlap
   math; overlapping-interval slots would be a spec change.
4. **No payment.** If it ever arrives, adopt the reserve/confirm +
   idempotent-webhook pattern from the architecture doc, not ad-hoc flags.

## Risks

- None structural — this feature exists to prove there aren't any. If it
  takes much more than an afternoon, that overrun is itself a finding about
  the platform pattern (record it in SC-004).

# Feature Specification: turf-service (booking)

**Feature Branch:** `004-turf-service` | **Created:** 2026-07-07 | **Status:** Done
**Input:** Venue/slot/booking CRUD with a hard no-double-booking guarantee.
Deliberately the simplest service — it measures how cheap adding a service
has become (target: ~an afternoon). Interview-scale evolution documented in
[docs/architecture/ticketmaster.md](../../docs/architecture/ticketmaster.md).

## User Scenarios & Testing

### User Story 1 — Browse venues and slots (Priority: P1)

A user lists venues and sees which time slots are available.

**Acceptance scenarios:**
1. **Given** seeded venues with slots, **When** GET `/bookings/venues`,
   **Then** venues return with their slots and availability.
2. **Given** a slot already booked, **Then** it shows as unavailable in the
   listing.

### User Story 2 — Book a slot, no double-booking (Priority: P1)

A user books an available slot; a second booking of the same slot must fail
even under concurrent requests.

**Why this priority:** The consistency guarantee is this service's entire
reason to exist (architecture doc §7: enforce in the database, not app
memory).

**Acceptance scenarios:**
1. **Given** an available slot, **When** alice POSTs `/bookings` for it,
   **Then** `201` with her booking.
2. **Given** alice booked the slot, **When** bob books the same slot,
   **Then** `409`.
3. **Given** two **simultaneous** requests for the same slot, **Then**
   exactly one succeeds (verified with a concurrent test, not just
   sequential).

### User Story 3 — Manage my bookings (Priority: P2)

A user lists their bookings and cancels one, freeing the slot.

**Acceptance scenarios:**
1. **Given** alice has bookings, **When** GET `/bookings/mine`, **Then** only
   hers return (from token `sub`, not a query param).
2. **Given** alice cancels a booking, **Then** the slot becomes bookable by
   others; cancelling bob's booking as alice → `403`.

### User Story 4 — Integration demo: plug turf into a separate project (Priority: P2)

A developer adds bookings to their own Kong-fronted project via the turf
plug kit (+ auth kit).

**Acceptance scenarios:**
1. **Given** a fresh host project with Kong, **When** auth + turf kits are
   composed in and set up, **Then** browse → book → conflict → cancel flows
   pass through the host's Kong.
2. **Given** the demo passed, **Then** zero service-code changes were made
   (Constitution Art. VII).

### Edge Cases

- Booking a nonexistent slot → `404`.
- Cancelling an already-cancelled booking → idempotent `204`/`200`.
- Slot in the past → `400`.

## Requirements

### Functional Requirements

- **FR-001:** Endpoints under `/bookings`: `GET /bookings/venues`,
  `POST /bookings`, `GET /bookings/mine`, `DELETE /bookings/{id}`.
- **FR-002:** Double-booking MUST be prevented by a **database-level
  constraint** (unique active booking per slot) inside one transaction —
  not by an application-level check-then-insert.
- **FR-003:** Identity from token `sub`; jwt + rate limiting at Kong; zero
  auth code (Art. II).
- **FR-004:** Owns `bookings-db` (Art. I); ships a plug kit under compose
  profile `turf` (Art. IV).

### Key Entities

- **Venue** — id, name, location.
- **Slot** — id, venueId, startTime, endTime.
- **Booking** — id, slotId, username, status (active/cancelled), createdAt;
  at most one *active* booking per slot.

## Success Criteria

- **SC-001:** book / list / cancel flows pass through Kong on
  `--profile turf`.
- **SC-002:** Concurrent double-booking test: N parallel requests → exactly
  1 success, N−1 conflicts.
- **SC-003:** `examples/turf-standalone/smoke.sh` passes with zero service
  code changes.
- **SC-004:** Wall-clock build time for this whole feature recorded in
  tasks.md — the platform's "cost of adding a service" metric.

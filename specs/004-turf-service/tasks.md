# Tasks: turf-service (booking)

**Input:** [spec.md](spec.md), [plan.md](plan.md)
**Prerequisite:** feature 001 complete. Record start/end time (SC-004).

## Phase 1 — Setup

- [x] T001 Scaffold `turf-service/` + `bookings-db` + compose profile `turf`
      (copy 002 scaffold; note how long this takes)
- [x] T002 [P] Copy JWT-decode helper

## Phase 2 — User Story 1: venues & slots (P1)

- [x] T003 [US1] Entities + repos: Venue, Slot; seed data (data.sql or
      CommandLineRunner)
- [x] T004 [US1] `GET /bookings/venues` with slots + availability (join on
      active bookings)
- [x] T005 [US1] **Checkpoint:** listing shows availability correctly

## Phase 3 — User Story 2: booking with conflict guarantee (P1)

- [x] T006 [US2] Booking entity; partial unique index
      `bookings(slot_id) WHERE status='active'` in schema migration
- [x] T007 [US2] `POST /bookings` — insert, map constraint violation → `409`;
      `404` unknown slot; `400` past slot
- [x] T008 [US2] Concurrent test: N parallel booking attempts on one slot →
      exactly 1 success (script or parallel curl in smoke)
- [x] T009 [US2] **Checkpoint:** SC-002 green

## Phase 4 — User Story 3: my bookings & cancel (P2)

- [x] T010 [US3] `GET /bookings/mine` (username from `sub`)
- [x] T011 [US3] `DELETE /bookings/{id}` — status flip to cancelled, `403` on
      others' bookings, idempotent re-cancel; slot immediately rebookable
- [x] T012 [US3] **Checkpoint:** US3 scenarios pass

## Phase 5 — Plug kit

- [x] T013 [P] `turf-service/plug/kong-setup.sh` (`/bookings` + jwt + rate
      limiting)
- [x] T014 [P] `turf-service/plug/compose.plug.yml` (image + bookings-db)
- [x] T015 [P] `turf-service/plug/smoke.sh` — browse → book → conflict →
      mine → cancel → rebook
- [x] T016 Thin wrapper `kong/setup-turf.sh`

## Phase 6 — User Story 4: integration demo (P2, Art. VII)

- [x] T017 [US4] `examples/turf-standalone/` — fresh Kong + auth kit + turf
      kit, images only, README with commands
- [x] T018 [US4] **Checkpoint (feature exit):** standalone smoke green, zero
      service-code changes; record total wall-clock time here → SC-004:
      ~0.75 hours

## Dependencies

- Phases sequential except [P] tasks; Phase 6 last.

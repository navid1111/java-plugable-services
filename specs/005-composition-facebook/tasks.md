# Tasks: composition demo ("facebook")

**Input:** [spec.md](spec.md)
**Prerequisite:** features 001, 002, 003 complete (their plug kits exist).

## Phase 1 — User Story 1: one-command assembly (P1)

- [ ] T001 [US1] Verify `docker compose --profile tweeter --profile chat up`
      brings up kong + auth + tweeter + chat with their DBs; fix
      profile/depends_on wiring only in root compose (never in services)
- [ ] T002 [US1] Run auth, tweeter, chat `kong-setup.sh` scripts against the
      assembly; confirm `/bookings` is absent (turf not enabled)
- [ ] T003 [US1] **Checkpoint:** all three prefixes answer through Kong;
      profiles start in either order

## Phase 2 — User Story 2: single-login static page (P1)

- [ ] T004 [US2] Create `examples/facebook/index.html` — login form, post
      composer + feed list, chat box over WebSocket; vanilla JS, token in
      memory
- [ ] T005 [US2] **Checkpoint:** login once → create post → send/receive chat
      message, same token; 401 path uniform when token cleared

## Phase 3 — Proof & record

- [ ] T006 Confirm `git diff --stat` is clean across `auth-service/`,
      `tweeter-service/`, `whatsapp-service/` → SC-001
- [ ] T007 Record commands + transcript/screenshot in README → SC-002

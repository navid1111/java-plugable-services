# Tasks: whatsapp-service (chat)

**Input:** [spec.md](spec.md), [plan.md](plan.md)
**Prerequisite:** feature 001 complete (tokens available).

## Phase 1 — Setup + risk spike

- [ ] T001 Scaffold `whatsapp-service/` + `chats-db` + compose profile `chat`
      (copy service scaffold from 002)
- [ ] T002 **Risk spike (do first):** minimal echo WebSocket at `/chat/ws`,
      Kong route + jwt plugin → prove valid token upgrades, invalid gets 401
      through Kong. If it fails, adopt the token-in-query fallback and update
      plan.md before continuing
- [ ] T003 [P] Copy JWT-decode helper into the service

## Phase 2 — User Story 1: chats + history REST (P1)

- [ ] T004 [US1] Entities + repos: Chat, ChatParticipant (unique pair, ≤100
      check), Message, InboxEntry
- [ ] T005 [US1] `POST /chat/chats` (participants list, cap → `400`),
      `GET /chat/chats` (mine only)
- [ ] T006 [US1] `GET /chat/chats/{id}/messages?cursor=` — newest-first,
      composite cursor, `403` for non-participants
- [ ] T007 [US1] **Checkpoint:** US1 scenarios pass with seeded messages

## Phase 3 — User Story 2: realtime path (P1)

- [ ] T008 [US2] Session registry: `username → Set<session>` populated at
      upgrade (username from verified JWT), cleaned on close
- [ ] T009 [US2] `sendMessage` handling: validate sender is participant →
      persist Message + InboxEntry per recipient → push `newMessage` to
      connected participants
- [ ] T010 [US2] Client ack handling → mark InboxEntry delivered; unknown ack
      ids ignored
- [ ] T011 [US2] **Checkpoint:** two clients exchange messages live through
      Kong; persistence precedes push (verify row exists even if push is
      forced to fail)

## Phase 4 — User Story 3: offline delivery (P1)

- [ ] T012 [US3] On-connect replay: query undelivered InboxEntries for the
      user, send in `createdAt` order, mark on ack
- [ ] T013 [US3] Scheduled cleanup job: delete delivered + >30-day rows
      (`@Scheduled`, daily)
- [ ] T014 [US3] **Checkpoint:** disconnect → 3 sends → reconnect → ordered
      replay → delivered flags set

## Phase 5 — Plug kit

- [ ] T015 [P] `whatsapp-service/plug/kong-setup.sh` — `/chat` route (REST +
      WS) + jwt + rate limiting, idempotent
- [ ] T016 [P] `whatsapp-service/plug/compose.plug.yml` (image + chats-db,
      profile `chat`)
- [ ] T017 [P] `whatsapp-service/plug/smoke.sh` — REST flow + scripted
      two-client WS exchange + offline replay (e.g., `websocat`/node script)
- [ ] T018 Thin wrapper `kong/setup-chat.sh`

## Phase 6 — User Story 4: integration demo (P2, Art. VII)

- [ ] T019 [US4] `examples/chat-standalone/` — fresh Kong + auth kit + chat
      kit, images only
- [ ] T020 [US4] README with exact commands incl. the WS client used by smoke
- [ ] T021 [US4] **Checkpoint (feature exit):** standalone smoke green incl.
      WS through host Kong, zero service-code changes → SC-004

## Dependencies

- T002 gates the whole feature (fallback decision changes T008/T015).
- Phase 2 → 3 → 4 sequential; Phase 5 tasks parallel; Phase 6 last.

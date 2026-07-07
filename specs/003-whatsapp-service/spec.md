# Feature Specification: whatsapp-service (chat)

**Feature Branch:** `003-whatsapp-service` | **Created:** 2026-07-07 | **Status:** Draft
**Input:** Realtime 1:1 and group chat behind Kong — REST for history,
WebSocket for live delivery, guaranteed offline delivery via an inbox.
Interview-scale evolution documented in
[docs/architecture/whatsapp.md](../../docs/architecture/whatsapp.md).

## User Scenarios & Testing

### User Story 1 — Create chats and read history (Priority: P1)

A user creates a 1:1 or group chat (≤100 participants), lists their chats,
and pages backward through message history.

**Independent test:** REST only — no WebSocket needed; seed messages
directly, then page history.

**Acceptance scenarios:**
1. **Given** a valid token, **When** alice creates a chat with bob, **Then**
   `201` and both see it in `GET /chat/chats`.
2. **Given** a chat with 100 participants, **When** one more is added,
   **Then** `400` (cap enforced).
3. **Given** a chat with messages, **When** GET
   `/chat/chats/{id}/messages?cursor=`, **Then** messages return
   newest-first with a cursor to older pages, no duplicates/gaps.
4. **Given** carol is not a participant, **When** she requests that chat's
   messages, **Then** `403`.

### User Story 2 — Realtime messaging (Priority: P1)

Two connected users exchange messages instantly through Kong.

**Independent test:** Two WebSocket clients (browser or CLI) connected via
Kong; message from one appears at the other in under a second.

**Acceptance scenarios:**
1. **Given** alice and bob both connected to `/chat/ws` with valid tokens,
   **When** alice sends a message to their chat, **Then** bob receives a
   `newMessage` push and the message is persisted **before** the push.
2. **Given** an invalid/missing token on the WebSocket upgrade request,
   **Then** the upgrade is rejected with `401` (at Kong).
3. **Given** a message is delivered and the client acks it, **Then** its
   inbox row is marked delivered.

### User Story 3 — Offline delivery (Priority: P1)

Messages sent while a user is offline arrive when they reconnect, up to 30
days later.

**Acceptance scenarios:**
1. **Given** bob is disconnected, **When** alice sends 3 messages, **Then**
   3 undelivered inbox rows exist for bob.
2. **Given** bob reconnects, **Then** the 3 messages replay in order over his
   WebSocket, and after his acks the rows are marked delivered.
3. **Given** inbox rows older than 30 days, **Then** the cleanup job removes
   them.

### User Story 4 — Integration demo: plug chat into a separate project (Priority: P2)

A developer adds realtime chat to their own Kong-fronted project via the
chat plug kit (+ auth kit), including the WebSocket path.

**Independent test:** `examples/chat-standalone/` — `smoke.sh` covers REST
history **and** a scripted two-client WebSocket exchange through the host's
Kong.

**Acceptance scenarios:**
1. **Given** a fresh host project, **When** auth + chat plug kits are
   composed in and their `kong-setup.sh` scripts run, **Then** US1–US3 flows
   pass — including the WS upgrade through the host's Kong.
2. **Given** the demo passed, **Then** zero service-code changes were made.

### Edge Cases

- WS upgrade with expired token → `401`; token expiring mid-connection does
  **not** drop the socket (verified once at upgrade — documented behavior).
- Sender includes a chatId they're not a participant of → message rejected.
- Client acks a message id it never received → ignored, no error.
- Duplicate delivery after a crash between push and ack is acceptable
  (at-least-once); clients dedupe by message id.

## Requirements

### Functional Requirements

- **FR-001:** REST under `/chat`: `POST /chat/chats` (1:1 and group, cap
  100), `GET /chat/chats`, `GET /chat/chats/{id}/messages?cursor=`.
- **FR-002:** WebSocket endpoint `/chat/ws`, proxied by Kong; jwt validated
  on the **upgrade request** by Kong's jwt plugin.
- **FR-003:** Send path MUST persist first (Message row + one InboxEntry per
  recipient), then push to connected participants; the ack marks the entry
  delivered — durability never depends on the push.
- **FR-004:** On reconnect, undelivered inbox entries replay in original
  order; client ack marks delivered.
- **FR-005:** Scheduled cleanup removes delivered/expired inbox rows after
  30 days.
- **FR-006:** Single chat node; in-memory `username → session(s)` registry;
  **no** Redis pub/sub (Constitution Art. VIII — only when >1 node exists).
- **FR-007:** Owns `chats-db`; participants stored as usernames only
  (Art. I, III). Ships a plug kit under compose profile `chat` (Art. IV).

### Key Entities

- **Chat** — id, name (groups), createdAt.
- **ChatParticipant** — chatId, username; unique pair; ≤100 per chat.
- **Message** — id, chatId, senderUsername, content, createdAt (server time).
- **InboxEntry** — messageId, recipientUsername, delivered flag, createdAt.

## Success Criteria

- **SC-001:** Two clients chat in realtime through Kong; delivery < 1s
  locally.
- **SC-002:** Kill one client, send messages, reconnect → full replay in
  order, then marked delivered.
- **SC-003:** WS upgrade through Kong verified early (phase risk): valid
  token connects, invalid gets `401`. If Kong cannot validate the upgrade,
  the documented fallback (token-in-query, validated app-side) is adopted
  and recorded in plan.md.
- **SC-004:** `examples/chat-standalone/smoke.sh` passes with zero service
  code changes (Art. VII).

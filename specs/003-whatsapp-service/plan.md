# Implementation Plan: whatsapp-service (chat)

**Branch:** `003-whatsapp-service` | **Date:** 2026-07-07
**Spec:** [spec.md](spec.md)

## Summary

Third service; introduces WebSockets. REST lands first (fully testable
alone), then the realtime path, then offline replay. The core correctness
pattern is **persist-then-push, mark-delivered-on-ack** — identical at one
node and at a thousand (see docs/architecture/whatsapp.md §7); the multi-node
machinery (pub/sub, consistent hashing) is explicitly deferred.

## Technical Context

- **Language/runtime:** Java 21, Spring Boot 4.1 + `spring-boot-starter-websocket`
- **Storage:** PostgreSQL `chats-db` (chat, chat_participant, message,
  inbox_entry); Postgres has no native TTL → scheduled cleanup job
- **Realtime:** single node; `ConcurrentHashMap<String, Set<WebSocketSession>>`
  keyed by username
- **Gateway:** Kong proxies the WS upgrade on `/chat/ws`; jwt plugin
  validates the upgrade request
- **Compose profile:** `chat`

## Constitution Check

| Article | Status |
|---------|--------|
| I — one DB per service | ✅ chats-db |
| II — auth at the edge | ✅ jwt on `/chat` incl. WS upgrade; service reads `sub` |
| III — identity by reference | ✅ participants are usernames; no profile copies |
| IV — plug kit | ✅ `whatsapp-service/plug/` |
| V — no service-to-service calls | ✅ none |
| VI — single ownership | ✅ chat membership lives here; distinct from tweeter's follow graph |
| VII — integration demo | ✅ `examples/chat-standalone/` incl. WS through host Kong |
| VIII — right-sized | ✅ one node, no Redis pub/sub, no per-device inbox |

## Design Decisions

1. **REST before WebSocket.** History endpoints are independently testable
   and de-risk the schema before any session code exists.
2. **Identity on the socket:** username extracted from the JWT once at
   upgrade (Kong already verified it) and attached to the session; token
   expiry mid-connection does not drop the socket — documented, matches
   spec edge case.
3. **At-least-once, dedupe client-side.** Crash between push and ack may
   redeliver; message ids make client dedupe trivial. Exactly-once is not
   attempted (see architecture doc §5.4 for why even WhatsApp doesn't).
4. **Ordering = server receive time.** Messages carry server-assigned
   `createdAt`; replay orders by it. No sequence numbers on one node.
5. **Inbox rows for all recipients, delivered flag** (not delete-on-ack):
   keeps the 30-day retention question orthogonal to delivery, and the
   cleanup job handles both delivered and expired rows.

## Risks

- **Kong + WS upgrade + jwt (the phase risk):** test in the first days of
  the feature, not the last. Fallback recorded in spec SC-003:
  token-in-query-param validated app-side. Whichever path wins gets written
  back into this plan.
- **Spring Boot 4.1 WebSocket API drift** vs. older examples — verify the
  starter's handler registration early alongside the Kong test.
- **Session leak on unclean disconnect:** rely on container-level TCP
  timeouts initially; heartbeats are a documented later hardening
  (architecture doc §5.3), not in scope.

# Interview Preparation Guide

This document contains technical, architectural, and project-based interview
questions based on the `whatsapp-service`.

## 1. Project Architecture and Design Decisions

**Q: What problem does `whatsapp-service` solve in this platform?**

**Answer:** It provides reusable chat functionality behind Kong: creating 1:1 or
group chats, reading message history, sending realtime WebSocket messages, and
replaying messages that were sent while a recipient was offline.

**Q: Why does chat use both REST and WebSockets instead of WebSockets for
everything?**

**Answer:** REST is a better fit for request/response workflows like creating
chats, listing chats, and paging history. WebSockets are a better fit for
realtime bidirectional delivery. Splitting the API this way keeps history easy
to test and page while keeping live delivery low-latency.

**Q: Why is Kong responsible for JWT verification?**

**Answer:** JWT verification is a cross-cutting edge concern. Kong rejects
missing, invalid, or expired tokens before traffic reaches the service. The chat
service then only decodes the `sub` claim to identify the current username.

**Q: Is `JwtHelper` in `whatsapp-service` verifying signatures?**

**Answer:** No. It decodes the JWT payload to read the subject. Signature and
expiration verification happen in Kong's JWT plugin. This is safe only because
protected `/chat` traffic is expected to pass through Kong.

**Q: What does the plug kit prove for `whatsapp-service`?**

**Answer:** The plug kit proves chat can be mounted into a separate Kong-fronted
host project using only its Docker image, Compose fragment, Kong setup script,
and smoke test. `examples/chat-standalone/` validates this with zero service
code changes.

**Q: Why does the chat service have its own `chats-db`?**

**Answer:** It owns its own data boundary. Auth owns users, Kong owns gateway
configuration, tweeter owns posts and follows, and chat owns chats, messages,
and delivery state. Separate databases reduce coupling and make services easier
to reuse independently.

## 2. WebSocket and Gateway Questions

**Q: How does a WebSocket connection get authenticated?**

**Answer:** The client sends an HTTP upgrade request to `/chat/ws` with
`Authorization: Bearer <token>`. Kong applies the JWT plugin to that upgrade
request. If valid, Kong proxies the upgrade to the service. The service's
`JwtHandshakeInterceptor` decodes the username and stores it in the WebSocket
session attributes.

**Q: Why store the username in WebSocket session attributes?**

**Answer:** WebSocket frames do not include HTTP request headers after the
upgrade. The handler needs a stable authenticated identity for later `sendMessage`
and `ack` frames, so the username is attached to the session during the
handshake.

**Q: What happens if a token expires while the WebSocket is already open?**

**Answer:** The current design checks the token only at the upgrade request. If
the token expires mid-connection, the socket stays open. That behavior is
documented in the spec and keeps the first implementation simple.

**Q: Why does the smoke test use a custom Node WebSocket client?**

**Answer:** The smoke test must send an `Authorization` header during the
WebSocket upgrade. The repo's `ws-smoke.mjs` performs a raw HTTP upgrade and
manual WebSocket framing, so it can prove Kong's JWT plugin protects the
WebSocket path.

**Q: What events does the WebSocket protocol support?**

**Answer:** Clients send `sendMessage` and `ack`. The server sends `newMessage`,
`messageSent`, `ack`, and `error`.

## 3. Delivery Guarantees

**Q: What is the most important correctness rule in this service?**

**Answer:** Persist first, push second. The service writes the `Message` row and
recipient `InboxEntry` rows before sending `newMessage` over WebSocket.

**Q: Why persist before pushing?**

**Answer:** WebSocket delivery is best-effort. A recipient may disconnect or the
push may fail. If the message is already persisted with an inbox row, it can be
replayed when the recipient reconnects.

**Q: Is the service exactly-once or at-least-once?**

**Answer:** It is at-least-once. If the server pushes a message and crashes
before receiving the ACK, that message can replay later. Clients should dedupe
by `message.id`.

**Q: How does ACK work?**

**Answer:** The recipient sends `{ "type": "ack", "messageId": ... }`. The
service finds the inbox row for that message and recipient and marks it
delivered. Duplicate or unknown ACKs are harmless and ignored.

**Q: Why not delete the inbox row immediately when ACKed?**

**Answer:** The implementation marks rows delivered first, then a scheduled
cleanup job deletes delivered or expired rows. This keeps delivery logic simple
and separates correctness from retention cleanup.

**Q: What happens when Bob is offline and Alice sends messages?**

**Answer:** The service persists messages and creates inbox rows for Bob with
`delivered=false`. Since Bob has no open session, no live push occurs. When Bob
reconnects, the service queries undelivered inbox rows and sends them in order.

**Q: How is offline replay ordered?**

**Answer:** Replay is ordered by inbox creation time ascending, with ID as a
tie-breaker. In this single-node design, that corresponds to server receive
order.

## 4. Data Model and Persistence

**Q: What are the main tables?**

**Answer:** `chats`, `chat_participants`, `messages`, and `inbox_entries`.

**Q: Why does `chat_participants` have a unique `(chat_id, username)` constraint?**

**Answer:** A user should not appear twice in the same chat. The unique
constraint enforces that invariant at the database level.

**Q: Why does `inbox_entries` have a unique `(message_id, recipient_username)`
constraint?**

**Answer:** Each recipient should have at most one delivery-tracking row for a
given message. This prevents duplicate replay state.

**Q: Why store usernames instead of user IDs?**

**Answer:** The auth token subject is the username, and the platform uses
identity by reference. Chat does not call auth or copy user profile data. It
stores the stable identity value it receives from the verified token.

**Q: Why use Postgres instead of DynamoDB like a massive WhatsApp design might?**

**Answer:** This project is a local, right-sized microservice platform. Postgres
fits the current scale, integrates easily with Spring Data JPA, and is already
used by the other services. The design borrows the durable inbox pattern, not
the entire hyperscale storage stack.

**Q: How does the service clean old inbox rows?**

**Answer:** `ChatService.cleanupInbox()` runs daily with `@Scheduled` and deletes
rows that are delivered or older than 30 days. This replaces DynamoDB-style TTL
with a Postgres-friendly scheduled cleanup.

## 5. Pagination and Query Design

**Q: Why use cursor pagination for message history?**

**Answer:** Cursor pagination is stable while new messages are inserted. Offset
pagination can skip or duplicate rows because row positions shift as new
messages arrive.

**Q: Why does the message cursor include both `createdAt` and `id`?**

**Answer:** Timestamps can tie. Ordering by `(created_at DESC, id DESC)` creates
a total order. The cursor needs both values to continue precisely from the last
message on the previous page.

**Q: Why is history newest-first while offline replay is oldest-first?**

**Answer:** History is optimized for a chat screen loading recent messages first.
Offline replay is optimized for delivery order, so messages sent while the user
was offline arrive in the same order they were created.

## 6. Spring Boot Technical Questions

**Q: Why use `@Transactional` in `ChatService`?**

**Answer:** Message sends must coordinate multiple writes: one `Message` and
multiple `InboxEntry` rows. A transaction keeps those writes consistent. ACK and
cleanup also modify delivery state and should run inside clear transaction
boundaries.

**Q: Why keep business logic out of `ChatWebSocketHandler`?**

**Answer:** The handler should parse frames and send frames. Rules like
participant authorization, persistence, ACK updates, and cleanup belong in
`ChatService` so REST and WebSocket paths share one business layer.

**Q: Why use `ConcurrentHashMap` in `SessionRegistry`?**

**Answer:** WebSocket sessions can connect, disconnect, and receive sends from
different threads. `ConcurrentHashMap` and concurrent sets make session tracking
safe for the single-node concurrent runtime.

**Q: Why synchronize on a `WebSocketSession` before sending?**

**Answer:** Concurrent writes to the same session can interleave. Synchronizing
around `session.sendMessage()` keeps frame writes for that session orderly.

**Q: Why does `ChatController` catch domain exceptions and convert them to HTTP
statuses?**

**Answer:** REST clients need clear HTTP semantics. Non-participant access maps
to `403`, missing chat maps to `404`, and invalid input maps to `400`.

## 7. Scaling and Trade-Off Questions

**Q: Why is there no Redis Pub/Sub?**

**Answer:** There is only one chat node. Redis Pub/Sub solves cross-node routing:
server A needs to reach a user connected to server B. With one node, every live
connection is already in the local `SessionRegistry`.

**Q: When would Redis Pub/Sub become necessary?**

**Answer:** When the system runs multiple chat-service instances. A message sent
to one instance may need to be pushed to a recipient connected to another
instance. Pub/Sub or another routing layer would bridge that gap.

**Q: What changes for multi-device support?**

**Answer:** Inbox rows should become per-client or per-device instead of
per-user. A message would need delivery tracking for every device that should
receive it.

**Q: What changes for stronger ordering?**

**Answer:** The service could add per-chat sequence numbers. Clients could then
detect gaps and request repair. The current design orders by server receive time,
which is enough for the single-node scope.

**Q: What changes for production-grade connection health?**

**Answer:** Add ping/pong heartbeats, close dead sockets proactively, and possibly
periodic client sync as a backstop for missed pushes.

**Q: How would media attachments be added?**

**Answer:** Use pre-signed URLs. The client uploads media directly to blob
storage, then sends a message containing metadata and the attachment URL. The
chat service should not proxy large media bytes.

## 8. Security and Boundary Questions

**Q: Can a client spoof `senderUsername` in a WebSocket message?**

**Answer:** No. The sender username comes from the authenticated WebSocket
session, not from the frame body.

**Q: Can Alice send to a chat she is not part of?**

**Answer:** No. `ChatService.sendMessage()` calls `requireParticipant()` before
persisting. If Alice is not a participant, the server returns a WebSocket `error`
event.

**Q: Can Carol read Alice and Bob's history?**

**Answer:** No. `GET /chat/chats/{id}/messages` checks membership and returns
`403 Forbidden` for non-participants.

**Q: Why does the service not call auth-service to validate participant names?**

**Answer:** The platform avoids service-to-service coupling. Chat stores
usernames by reference. In a larger product, a frontend or composition layer
could provide user search, but chat itself does not need auth database access.

**Q: What are the most important tests for this service?**

**Answer:** JWT rejection at Kong, chat creation limits, participant-only
history, stable cursor paging, WebSocket upgrade with valid and invalid tokens,
live delivery, ACK state changes, offline replay order, and standalone plug-kit
smoke testing.

## 9. Short System Design Summary

A strong interview summary:

The service uses Kong for JWT verification and routes `/chat` to a Spring Boot
chat backend. REST endpoints create chats and read history. WebSocket connections
are authenticated during the upgrade; the username is stored in the session. On
send, the service checks chat membership, persists the message and per-recipient
inbox rows, then pushes to connected recipients. Recipients ACK messages, which
marks inbox rows delivered. If a recipient is offline, inbox rows remain
undelivered and replay on reconnect. The current design is single-node with an
in-memory session registry; Redis Pub/Sub, per-device inboxes, heartbeats, and
media uploads are documented future scaling steps.

# WhatsApp — Architecture Breakdown

> Source: [Hello Interview — WhatsApp](https://www.hellointerview.com/learn/system-design/problem-breakdowns/whatsapp)
> Maps to: **whatsapp-service** (`/chat`) in this repo — REST history + WebSocket realtime, undelivered-message inbox.

## 1. Requirements

### Functional (core)
1. Group chats (2–100 participants)
2. Send/receive messages in real time
3. Offline delivery — messages held up to 30 days
4. Media attachments

Out of scope: audio/video calls, business features, registration.

### Non-functional (core)
1. Delivery latency < 500ms
2. **Guaranteed deliverability** — messages must not be lost
3. Billions of users, high throughput (~100K writes/sec estimated)
4. Minimal centralized storage duration (delete once delivered)
5. Resilient to component failures

## 2. Core Entities

- **User** — account holder, possibly multiple devices
- **Client** — one device belonging to a user
- **Chat** — conversation of 2–100 participants
- **ChatParticipant** — membership record
- **Message** — content + timestamp

## 3. API (WebSocket commands, not REST)

Client → server: `createChat`, `sendMessage` (with optional attachments), `createAttachment`, `modifyChatParticipants`
Server → client: `chatUpdate`, `newMessage` (requires **ACK** from the client)

## 4. High-Level Architecture (single chat server first)

```
Clients ⇄ L4 Load Balancer ⇄ Chat Server
                                │  in-memory map: userId → WebSocket connection(s)
                                ↓
                            DynamoDB
              Chat / ChatParticipant / Message / Inbox tables
```

**Data model:**
- `Chat` — PK chatId
- `ChatParticipant` — composite key (chatId, participantId) + GSI for "which chats am I in"
- `Message` — durable message content
- `Inbox` — one row per undelivered (message, client), **30-day TTL**

**Realtime send path:**
1. Sender emits `sendMessage` over its WebSocket
2. Server **persists first**: Message row + Inbox rows for every recipient client
3. Server looks up recipient connections in its in-memory map and pushes `newMessage`
4. Recipient client ACKs → server deletes that Inbox row

**Offline path (on reconnect):** query the client's Inbox → fetch full messages → deliver → delete after ACK. Durability comes from "persist before push"; the push itself is best-effort.

**Media:** pre-signed URLs. Client uploads directly to blob storage (S3), sends the opaque URL in the message; recipients download via pre-signed URL. The chat server never proxies bytes.

## 5. Scaling Deep Dives

### 5.1 Beyond one server: Redis Pub/Sub + clustering

A single host can't hold 200M WebSocket connections.

- Multiple chat servers; users assigned by **consistent hash of userId** (ring managed via ZooKeeper/etcd)
- **Redis Pub/Sub channel per user** for cross-server routing: the sender's server publishes; whichever server holds the recipient's connection is subscribed and pushes it down
- Write path stays: durable storage (Message + Inbox) first, then Pub/Sub best-effort
- For large groups (>25 members), switch to a per-chat channel to cut publish overhead

### 5.2 Multiple devices per user

- Add a `Clients` table (recommend a 3-device cap)
- Inbox becomes **per-client**, not per-user; delivery targets every active client

### 5.3 Detecting dead WebSocket connections

**Application-level heartbeats**: server pings every 10–30s; client must pong within ~5s; a missed heartbeat closes the connection server-side, forcing a clean reconnect. Fallback: ACK timeouts with retry before declaring the connection dead.

### 5.4 Redis Pub/Sub loses messages (at-most-once)

Tiered reliability, since Pub/Sub is best-effort:
1. Periodic client sync (30–60s poll of the Inbox) as a backstop
2. **Per-chat sequence numbers** with client-side gap detection
3. Piggyback the latest sequence number on heartbeat pings — a gap triggers an immediate sync

Worst-case detection window ≈ one heartbeat interval.

### 5.5 Message ordering

No complex reordering: order by **NTP-synchronized server receive time**. Occasionally a message renders "above" an earlier one — accepted UX trade-off.

### 5.6 "Last seen"

- Store only the **last disconnect** timestamp (one row per user, written on disconnect — not per heartbeat)
- Conditional writes prevent races
- "Online now" is answered live: forward `getLastSeen` over Pub/Sub to the user's chat server; client merges DB timestamp + live answer

## 6. Technology stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Load balancer | L4 | Route/hold WebSocket connections |
| Chat servers | Custom | Connection map, routing |
| Messages, Inbox, metadata | DynamoDB | Durable store, 100K+ writes/sec, native TTL cleanup |
| Cross-server routing | Redis Pub/Sub | Lightweight best-effort forwarding |
| Media | S3-like blob store | Pre-signed URL upload/download |
| Hash-ring coordination | ZooKeeper/etcd | Consistent server assignment |

## 7. Mapping to whatsapp-service (this repo)

| Interview design | whatsapp-service (right-sized) |
|------------------|--------------------------------|
| L4 LB + WebSocket command API | **Kong proxies the WS upgrade** at `/chat/ws`; jwt plugin validates the token on the upgrade request (test early — plan Phase 3 risk; fallback is token-in-query validated app-side) |
| WebSocket-only API | Hybrid: **REST for history** (`GET /chat/chats`, `GET /chat/chats/{id}/messages?cursor=`), WebSocket for realtime — simpler to build and debug |
| DynamoDB tables | Postgres `chats-db`: chat, participant (≤100 cap), message, inbox tables |
| Inbox with 30-day TTL | Inbox rows with `delivered` flag + a **scheduled cleanup job** (30 days) — Postgres has no native TTL |
| Persist-then-push, delete on ACK | **Adopted as-is** — this is the core correctness pattern: write Message + Inbox, then push; replay undelivered on reconnect; mark on client ack |
| Consistent hashing + Redis Pub/Sub | **Deliberately skipped** (spec: single chat node, "no Redis pub/sub until there is >1 node") — the in-memory userId → session map alone is sufficient on one node |
| Heartbeats, sequence gaps | Optional hardening later; on one node the pub/sub loss problem doesn't exist |
| Per-client inbox, 3-device cap | Start per-user; split per-client only if multi-device becomes real |
| Pre-signed URL media | Out of scope for now (spec: no media upload) |

**The transferable lesson:** deliverability comes from the storage pattern, not the routing layer — *persist to Message + Inbox before pushing, delete only on ACK*. That pattern is identical at one node and at a thousand; everything else (pub/sub, hash rings, heartbeat sync) is machinery for fanning the push across servers, which we add only when node #2 exists.

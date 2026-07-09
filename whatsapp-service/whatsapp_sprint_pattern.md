# Spring Boot Patterns and Code Analysis

The `whatsapp-service` follows the layered Spring Boot pattern established by
`auth-service` and `tweeter-service`, then adds a WebSocket layer for realtime
delivery. The most important business pattern is durable delivery:

```text
persist Message + InboxEntry rows, push over WebSocket, mark delivered on ACK
```

## Core Spring Boot Patterns

1. **Layered Architecture**
   The service is split into controller, service, repository, model, security,
   configuration, and WebSocket packages.

2. **Hybrid REST + WebSocket API**
   REST handles chat creation, chat listing, and history reads. WebSocket handles
   realtime message sending, recipient pushes, ACKs, and offline replay.

3. **Constructor Injection**
   Controllers, services, handlers, and config classes receive dependencies
   through constructors. This makes dependencies explicit and testable.

4. **Spring Data JPA Repository Pattern**
   Entities map to Postgres tables. Repositories provide generated CRUD behavior
   plus custom query methods for chat listing, cursor paging, inbox replay, and
   cleanup.

5. **Transactional Business Logic**
   `ChatService` owns transaction boundaries. Writes such as chat creation,
   message sending, ACK updates, and cleanup run inside transactions.

6. **Right-Sized In-Memory Realtime State**
   `SessionRegistry` uses a `ConcurrentHashMap<String, Set<WebSocketSession>>`
   because the service is intentionally single-node. No Redis Pub/Sub is needed
   until more than one chat node exists.

7. **Scheduled Maintenance**
   Postgres does not have native TTL. The service uses `@Scheduled` to remove
   delivered or expired inbox rows once a day.

## Package Structure

```text
com.example.whatsapp
|-- config
|   `-- WebSocketConfig.java
|-- controller
|   |-- ChatController.java
|   `-- HealthController.java
|-- model
|   |-- Chat.java
|   |-- ChatParticipant.java
|   |-- InboxEntry.java
|   `-- Message.java
|-- repository
|   |-- ChatParticipantRepository.java
|   |-- ChatRepository.java
|   |-- InboxEntryRepository.java
|   `-- MessageRepository.java
|-- security
|   |-- JwtHandshakeInterceptor.java
|   `-- JwtHelper.java
|-- service
|   `-- ChatService.java
`-- websocket
    |-- ChatWebSocketHandler.java
    `-- SessionRegistry.java
```

## File-by-File Breakdown

### 1. `WhatsappApplication.java`

This is the JVM entry point.

- **Pattern:** `@SpringBootApplication` plus `@EnableScheduling`.
- **Why:** `@SpringBootApplication` starts component scanning and auto
  configuration. `@EnableScheduling` activates the daily inbox cleanup job.

### 2. `controller/HealthController.java`

This exposes the health endpoint used by Docker Compose.

- **Pattern:** Minimal REST controller.
- **Detail:** `GET /health` returns a simple successful response.
- **Why:** Compose health checks need a lightweight endpoint before routing
  traffic or running smoke tests.

### 3. `controller/ChatController.java`

This exposes the REST API under `/chat`.

- **Pattern:** `@RestController`, `@RequestMapping("/chat")`,
  `ResponseEntity`, and Java records for request bodies.
- **Endpoints:**
  - `POST /chat/chats`
  - `GET /chat/chats`
  - `GET /chat/chats/{id}/messages`
- **Detail:** It extracts the username from the `Authorization` header through
  `JwtHelper`, then delegates behavior to `ChatService`.
- **Why:** The controller owns HTTP concerns: request parsing, status codes, and
  response shape. The service owns business rules.

### 4. `config/WebSocketConfig.java`

This registers the WebSocket endpoint.

- **Pattern:** `@Configuration`, `@EnableWebSocket`, `WebSocketConfigurer`.
- **Detail:** It binds `ChatWebSocketHandler` to `/chat/ws`, attaches
  `JwtHandshakeInterceptor`, and allows origins for local development.
- **Why:** Spring MVC controllers do not handle WebSocket frames. WebSocket
  traffic needs a separate handler registration.

### 5. `security/JwtHelper.java`

This decodes the JWT payload and extracts `sub`.

- **Pattern:** Minimal downstream identity helper.
- **Detail:** It parses the token body with Jackson and reads the subject claim.
- **Why:** Kong has already verified signature and expiration for `/chat`
  requests. The service only needs the username identity reference.

Important boundary: this helper is not a replacement for edge JWT verification.
If traffic bypasses Kong, it can decode a token but does not prove it is signed
by the trusted secret.

### 6. `security/JwtHandshakeInterceptor.java`

This connects HTTP upgrade authentication to WebSocket sessions.

- **Pattern:** Spring WebSocket `HandshakeInterceptor`.
- **Detail:** Before the WebSocket upgrade completes, it reads the
  `Authorization` header, extracts the username, and stores it in session
  attributes.
- **Why:** After the upgrade, frames do not carry HTTP headers. The handler needs
  the authenticated username attached to the session.

### 7. `model/Chat.java`

This is the JPA entity for conversation metadata.

- **Pattern:** Object-Relational Mapping.
- **Detail:** Stores `id`, optional group `name`, and `createdAt`.
- **Why:** Chat metadata is separate from participants and messages so queries
  can compose around the chat ID.

### 8. `model/ChatParticipant.java`

This is the JPA entity for membership.

- **Pattern:** Entity with unique constraint and indexes.
- **Detail:** `(chat_id, username)` is unique. `username` and `chat_id` are
  indexed.
- **Why:** A user cannot be duplicated in the same chat, and the service needs
  efficient "which chats am I in?" and "who is in this chat?" lookups.

### 9. `model/Message.java`

This is the durable message entity.

- **Pattern:** Append-only message record.
- **Detail:** Stores `chatId`, `senderUsername`, `content`, and server
  `createdAt`.
- **Why:** Message history should not depend on WebSocket delivery. Once saved,
  the message is durable and visible through history.

### 10. `model/InboxEntry.java`

This is the per-recipient delivery tracking entity.

- **Pattern:** Delivery ledger with unique constraint.
- **Detail:** `(message_id, recipient_username)` is unique. Each row tracks
  `delivered`, `createdAt`, and `deliveredAt`.
- **Why:** The service can replay undelivered messages after reconnect and mark
  each recipient independently delivered.

### 11. `repository/ChatRepository.java`

This repository handles chat lookups.

- **Pattern:** Spring Data JPA repository with native query.
- **Detail:** `findByParticipant()` joins `chats` to `chat_participants` and
  orders newest-first.
- **Why:** The "my chats" screen needs only chats where the current username is
  a participant.

### 12. `repository/ChatParticipantRepository.java`

This repository handles membership checks and participant reads.

- **Pattern:** Derived query methods.
- **Important methods:**
  - `existsByChatIdAndUsername`
  - `findByChatIdOrderByUsernameAsc`
  - `findByChatIdIn`
- **Why:** Membership is checked before history reads and message sends.

### 13. `repository/MessageRepository.java`

This repository handles history paging.

- **Pattern:** Native SQL for stable cursor pagination.
- **Detail:** Queries order by `created_at DESC, id DESC` and cursor by
  `(createdAt, id)`.
- **Why:** Timestamps can tie. The ID tie-breaker gives a total order and avoids
  duplicate or skipped rows between pages.

### 14. `repository/InboxEntryRepository.java`

This repository handles delivery state.

- **Pattern:** Derived methods plus JPQL delete query.
- **Important methods:**
  - `findByMessageIdAndRecipientUsername`
  - `findByRecipientUsernameAndDeliveredFalseOrderByCreatedAtAscIdAsc`
  - `deleteDeliveredOrExpired`
- **Why:** ACKs need a single inbox row, reconnect needs ordered undelivered
  rows, and cleanup needs a bulk delete.

### 15. `service/ChatService.java`

This is the business core.

- **Pattern:** `@Service` with `@Transactional` methods.
- **Responsibilities:**
  - create chats
  - list current user's chats
  - enforce participant authorization
  - page message history
  - persist messages and inbox entries
  - mark inbox entries delivered on ACK
  - read undelivered messages on reconnect
  - clean delivered or expired inbox rows
- **Why:** This keeps rules out of controllers and WebSocket handlers. Both REST
  and WebSocket paths reuse the same business logic.

### 16. `websocket/SessionRegistry.java`

This stores live WebSocket sessions by username.

- **Pattern:** Thread-safe in-memory registry.
- **Detail:** Uses `ConcurrentHashMap<String, Set<WebSocketSession>>`, allowing
  multiple open sessions per username.
- **Why:** On a single node, an in-memory map is enough to route pushes to
  connected recipients. The service removes closed or failed sessions.

### 17. `websocket/ChatWebSocketHandler.java`

This handles WebSocket lifecycle and events.

- **Pattern:** `TextWebSocketHandler`.
- **Lifecycle:**
  - `afterConnectionEstablished`: register session and replay undelivered
    messages
  - `handleTextMessage`: process `sendMessage` and `ack`
  - `afterConnectionClosed`: remove session from registry
- **Why:** WebSocket logic stays separate from REST controllers, while still
  delegating business decisions to `ChatService`.

## Key Design Patterns in the Implementation

### Persist-Then-Push

`ChatService.sendMessage()` saves the `Message` and recipient `InboxEntry` rows
before `ChatWebSocketHandler` pushes anything to recipients.

This means a failed WebSocket push does not lose the message. It remains in the
recipient's inbox and will replay on reconnect.

### At-Least-Once Delivery

The service marks delivery only when the recipient ACKs. If the server crashes
after pushing but before receiving ACK, the message may replay later.

This is intentional. At-least-once delivery is simpler and reliable. Clients can
deduplicate using `message.id`.

### Cursor Pagination

History uses cursor pagination instead of offset pagination.

Cursor:

```text
base64url(createdAt + "|" + id)
```

Query order:

```text
created_at DESC, id DESC
```

This keeps pagination stable while new messages are inserted.

### Identity by Reference

The service stores usernames, not copied user profiles. The username comes from
the JWT `sub` claim. This keeps auth and chat independently deployable.

### REST for Querying, WebSocket for Delivery

History and chat lists are easier to cache, test, and page over HTTP. Realtime
message delivery belongs over WebSocket. The service uses each protocol where it
is strongest.

## What Is Deliberately Not Implemented Yet

- Redis Pub/Sub for cross-node routing
- consistent hashing for assigning users to chat servers
- per-device inbox rows
- heartbeat ping/pong
- media attachments
- end-to-end encryption
- exactly-once delivery

These are real WhatsApp-scale concerns, but the project is currently a
single-node, interview-sized service. The durable inbox pattern is ready for
growth without adding that machinery too early.

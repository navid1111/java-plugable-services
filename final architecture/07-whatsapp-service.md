# whatsapp-service — Architecture

Owns the `/chat` prefix: **WhatsApp-style chat** = REST for history + **WebSocket for
real-time delivery**. Owns `chats-db`. Uses an **inbox/outbox** model so messages are durable
and deliverable even when a recipient is offline.

## Component / request flow

```mermaid
graph TB
    Client([Client]) -->|"REST (history):<br/>POST /chat/chats · GET /chat/chats<br/>GET /chat/chats/{id}/messages"| Kong["Kong Gateway (/chat = JWT)"]
    Client -.->|"WebSocket (live):<br/>ws /chat/ws?token=JWT"| WS

    Kong --> Tomcat

    subgraph Whatsapp["whatsapp-service (:8080)"]
        Tomcat["DispatcherServlet"]
        JwtH["JwtHelper"]
        CC["ChatController /chat/*"]

        subgraph WSlayer["WebSocket layer"]
            WSC["WebSocketConfig"]
            HS["JwtHandshakeInterceptor<br/>authenticate on connect"]
            WSH["ChatWebSocketHandler<br/>send / receive frames"]
            SReg["SessionRegistry<br/>userId → live session(s)"]
        end

        CS["ChatService<br/>create chat · post message ·<br/>fan-out to inbox · history"]

        subgraph Repos["Repositories"]
            CR["ChatRepository"]
            CPR["ChatParticipantRepository"]
            MR["MessageRepository"]
            IER["InboxEntryRepository"]
        end

        CEP["ChatEventPublisher"]
        ORC["WhatsappOutboxRelayConfig<br/>@Scheduled relay"]

        Tomcat --> JwtH --> CC --> CS
        WS[/"ws endpoint"/] --> WSC --> HS --> WSH
        WSH --> SReg
        WSH --> CS
        CS --> CR
        CS --> CPR
        CS --> MR
        CS --> IER
        CS -->|deliver if online| SReg
    end

    CR --> DB[("chats-db")]
    CPR --> DB
    MR --> DB
    IER --> DB
    CEP --> DB

    ORC -. "chat / message events<br/>(ChatEventTypes)" .-> MQ{{"RabbitMQ platform.events.v1"}}
```

## Domain model

- **`Chat`** — conversation: `name`, `createdAt`.
- **`ChatParticipant`** — membership: `chatId`, `username`/`userId`.
- **`Message`** — `chatId`, sender identity, `content`, `createdAt`.
- **`InboxEntry`** — per-recipient delivery record: `messageId`, `recipientUserId`, `delivered` flag, `deliveredAt` — the durable "unread/undelivered" queue.

## Responsibilities & contracts

- **REST history** — create chats, list my chats, page a chat's messages.
- **Real-time delivery** — clients open a WebSocket authenticated by JWT at handshake (`JwtHandshakeInterceptor`); `SessionRegistry` maps online users to sessions; `ChatWebSocketHandler` pushes new messages live.
- **Store-and-forward** — every message writes an `InboxEntry` per recipient; online recipients get an immediate push and the entry is marked `delivered`, offline ones pick it up later.

## Notable design choices

- **Two transports, one service** — REST for durable history, WebSocket for low-latency push; both share the same `ChatService` write path.
- **Inbox pattern for offline delivery** — persistence-first means no message is lost if a recipient is disconnected; delivery is a state transition on `InboxEntry`, not a fire-and-forget socket write.
- **JWT at handshake** — auth happens once on connect (query-token), so per-frame auth isn't needed and unauthenticated sockets never open.
- **Standalone-ish** — self-contained chat domain; emits events via outbox for any future integration but consumes none.

# Testing Guide and Terminal Commands

This document outlines the terminal commands required to run, configure, and
test the `whatsapp-service` behind Kong. This service is more complex than
auth and tweeter because it has both REST endpoints and a WebSocket endpoint.

## 1. Environment Setup

The chat service depends on JWTs issued by `auth-service` and verified by Kong.
Create a root `.env` file with the same JWT settings used by auth and Kong:

```bash
echo "JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
```

The secret must be at least 32 bytes for HS256. The issuer must match the Kong
JWT credential key, because Kong uses the token's `iss` claim to find the
verification secret.

## 2. Managing the Stack

The `whatsapp-service` is scoped under the `chat` profile. That profile also
starts `auth-service`, because chat endpoints require valid users and JWTs.

**Start the stack (Core + Auth + Chat):**

```bash
docker compose --profile chat up --build -d
```

**Check container health:**

```bash
docker compose --profile chat ps
```

Expected healthy services:

- `kong`
- `kong-database`
- `users-db`
- `auth-service`
- `chats-db`
- `whatsapp-service`

**Stop the stack without deleting volumes:**

```bash
docker compose --profile chat down
```

**Stop and clean up the stack including volumes:**

```bash
docker compose --profile chat down -v
```

Use `down -v` only when you intentionally want to delete Postgres data.

## 3. Configuring the Gateway

Configure core auth first, then register the protected `/chat` route:

```bash
./kong/setup-core.sh
./kong/setup-chat.sh
```

`setup-core.sh` registers:

- `/auth` route
- Kong consumer `springboot-auth`
- HS256 JWT credential matching `JWT_SECRET` and `JWT_ISSUER`

`setup-chat.sh` registers:

- Kong service `whatsapp-service`
- protected `/chat` route
- `jwt` plugin for REST and WebSocket upgrade requests
- rate limiting: 10 requests/minute, 100 requests/hour

## 4. Manual REST API Testing Using Curl

The API is exposed through Kong at `http://localhost:18000`. All `/chat`
endpoints require a valid JWT verified by Kong.

### A. Register Three Users

```bash
curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'

curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"secret123"}'

curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"carol","password":"secret123"}'
```

Expected output for each new user: `201 Created`.

### B. Log In and Extract Tokens

```bash
ALICE_TOKEN=$(curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

BOB_TOKEN=$(curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

CAROL_TOKEN=$(curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"carol","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
```

Confirm a token was extracted:

```bash
printf 'alice token length: %s\n' "${#ALICE_TOKEN}"
```

### C. Confirm Kong Rejects Missing Tokens

```bash
curl -i -s http://localhost:18000/chat/chats
```

Expected output: `401 Unauthorized`. This rejection happens at Kong before the
request reaches `whatsapp-service`.

### D. Create a Chat

```bash
curl -i -s -X POST http://localhost:18000/chat/chats \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice and bob","participants":["bob"]}'
```

Expected output: `201 Created` with a JSON body like:

```json
{
  "id": 1,
  "name": "alice and bob",
  "participants": ["alice", "bob"],
  "createdAt": "2026-07-07T..."
}
```

Extract the chat ID:

```bash
CHAT_ID=$(curl -s -X POST http://localhost:18000/chat/chats \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"manual smoke chat","participants":["bob"]}' \
  | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
```

### E. List My Chats

```bash
curl -i -s http://localhost:18000/chat/chats \
  -H "Authorization: Bearer $BOB_TOKEN"
```

Expected output: `200 OK` with only chats where Bob is a participant.

### F. Reject Non-Participant History Access

```bash
curl -i -s "http://localhost:18000/chat/chats/${CHAT_ID}/messages" \
  -H "Authorization: Bearer $CAROL_TOKEN"
```

Expected output: `403 Forbidden`, because Carol is not in Alice and Bob's chat.

### G. Read Message History

After sending messages over WebSocket, history can be read with cursor paging:

```bash
curl -i -s "http://localhost:18000/chat/chats/${CHAT_ID}/messages?pageSize=2" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK` with:

- `items`: newest-first messages
- `nextCursor`: present when older messages are available

Use the returned `nextCursor` to fetch the next page:

```bash
curl -i -s "http://localhost:18000/chat/chats/${CHAT_ID}/messages?pageSize=2&cursor=${CURSOR}" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

## 5. WebSocket Testing

The WebSocket endpoint is:

```text
ws://localhost:18000/chat/ws
```

The upgrade request must include:

```text
Authorization: Bearer <token>
```

The service uses a small JSON event protocol.

### Client to Server: Send a Message

```json
{
  "type": "sendMessage",
  "chatId": 1,
  "content": "hello over websocket"
}
```

Server replies to the sender:

```json
{
  "type": "messageSent",
  "data": {
    "message": {
      "id": 10,
      "chatId": 1,
      "senderUsername": "alice",
      "content": "hello over websocket",
      "createdAt": "2026-07-07T..."
    }
  }
}
```

Server pushes to connected recipients:

```json
{
  "type": "newMessage",
  "data": {
    "message": {
      "id": 10,
      "chatId": 1,
      "senderUsername": "alice",
      "content": "hello over websocket",
      "createdAt": "2026-07-07T..."
    }
  }
}
```

### Client to Server: Acknowledge Delivery

```json
{
  "type": "ack",
  "messageId": 10
}
```

Server replies:

```json
{
  "type": "ack",
  "data": {
    "messageId": 10
  }
}
```

### Scripted WebSocket Test

The repo includes a Node-based raw WebSocket smoke client. It intentionally uses
a manual HTTP upgrade so it can send the `Authorization` header.

```bash
node whatsapp-service/plug/ws-smoke.mjs \
  ws://localhost:18000/chat/ws \
  "$ALICE_TOKEN" \
  "$BOB_TOKEN" \
  "$CHAT_ID"
```

The script verifies:

- invalid token upgrade returns `401`
- Alice and Bob can both connect with valid tokens
- Alice sends a live message and Bob receives `newMessage`
- Bob sends `ack`, marking the inbox row delivered
- Bob disconnects
- Alice sends three offline messages
- Bob reconnects and receives the three messages in order
- Bob ACKs each replayed message

## 6. Automated Smoke Testing

Use the built-in smoke test for the full REST + WebSocket flow:

```bash
./whatsapp-service/plug/smoke.sh
```

The smoke test registers Alice, Bob, and Carol; logs them in; confirms Kong
rejects unauthenticated `/chat`; creates a chat; verifies non-participant
history returns `403`; runs the two-client WebSocket flow; and checks persisted
history with cursor paging.

Expected final output:

```text
WebSocket smoke passed.
Chat smoke test passed successfully.
```

## 7. Standalone Integration Demo

To prove the service can run independently, use the standalone demo. This is the
most important proof for the plug-kit design: chat is mounted into a separate
Kong-fronted host project with zero service-code changes.

From the root of the repo:

```bash
docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
docker build -t whatsapp-service:latest -f whatsapp-service/Dockerfile whatsapp-service/
```

Then:

```bash
cd examples/chat-standalone
echo "JWT_SECRET=super-secret-jwt-key-for-chat-demo-32-bytes" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
docker compose up -d
```

Configure Kong:

```bash
../../auth-service/plug/kong-setup.sh

curl -fsS -X PUT http://localhost:8001/consumers/springboot-auth

curl -fsS -X POST http://localhost:8001/consumers/springboot-auth/jwt \
  --data "algorithm=HS256" \
  --data "key=springboot-auth" \
  --data "secret=super-secret-jwt-key-for-chat-demo-32-bytes" \
  || echo "jwt credential already exists, skipping."

../../whatsapp-service/plug/kong-setup.sh
```

Run the smoke test:

```bash
../../whatsapp-service/plug/smoke.sh
```

Tear down:

```bash
docker compose down -v
```

## 8. Troubleshooting

### `401 Unauthorized` on `/chat`

Possible causes:

- token is missing
- token is expired
- `JWT_ISSUER` does not match Kong's JWT credential key
- `JWT_SECRET` used by auth does not match Kong's JWT credential secret
- `./kong/setup-core.sh` was not run

### WebSocket Upgrade Fails

Check that:

- `./kong/setup-chat.sh` was run
- the request path is exactly `/chat/ws`
- the upgrade includes `Authorization: Bearer <token>`
- Kong has the `jwt` plugin on `whatsapp-service`
- the `whatsapp-service` container is healthy

### Offline Replay Does Not Happen

Check that:

- the recipient was disconnected when messages were sent
- the sender and recipient are participants in the same chat
- the recipient did not ACK those messages already
- `inbox_entries.delivered` remains `false`

### History Cursor Looks Wrong

Message history is ordered by `created_at DESC, id DESC`. The cursor encodes the
last row's `(createdAt, id)` pair, so paging remains stable even when two
messages have the same timestamp.

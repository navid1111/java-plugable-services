# Facebook Composition Demo

This demo composes the already-built `auth-service`, `tweeter-service`, and
`whatsapp-service` into one Facebook-like product without changing any service
code. The composition happens through Docker Compose profiles, Kong plug kits,
and a small static page in this directory.

The page is vanilla HTML/CSS/JavaScript with no build step. It stores the JWT in
memory only. Browser WebSockets cannot set an `Authorization` header, so
`server.mjs` serves the page and proxies `/chat/ws?token=...` to Kong with the
same Bearer token header that the REST calls use.

## Start The Composed Stack

```bash
docker compose -p facebook --profile tweeter --profile chat up --build -d
./kong/setup-core.sh
./kong/setup-tweeter.sh
./kong/setup-chat.sh
```

The equivalent non-isolated command is:

```bash
docker compose --profile tweeter --profile chat up --build -d
```

I used `-p facebook` during verification so the run has fresh Compose volumes
and proves `/bookings` is absent when the turf profile is not enabled.
The stack was also restarted successfully as
`--profile chat --profile tweeter`, confirming profile order does not matter.

## Run The Smoke Test

Directly through Kong:

```bash
./examples/facebook/smoke.sh
```

Through the local static/proxy server:

```bash
node examples/facebook/server.mjs
KONG_URL=http://localhost:18080 WS_KONG_URL=ws://localhost:18080 ./examples/facebook/smoke.sh
```

Open the page at:

```text
http://localhost:18080
```

## Verified Transcript

Direct Kong smoke:

```text
Running facebook composition smoke against http://localhost:18000
[1/8] Confirming protected services reject missing tokens...
[2/8] Registering users...
[3/8] Logging in once as Alice...
[4/8] Creating a post with Alice's token...
[5/8] Reading posts with the same token...
[6/8] Creating a chat with Bob using the same token...
[7/8] Sending a chat message over WebSocket with Alice's token...
WebSocket send passed.
[8/8] Reading chat history with the same token...
Facebook composition smoke passed successfully.
```

Local page/proxy smoke:

```text
Running facebook composition smoke against http://localhost:18080
[1/8] Confirming protected services reject missing tokens...
[2/8] Registering users...
[3/8] Logging in once as Alice...
[4/8] Creating a post with Alice's token...
[5/8] Reading posts with the same token...
[6/8] Creating a chat with Bob using the same token...
[7/8] Sending a chat message over WebSocket with Alice's token...
WebSocket send passed.
[8/8] Reading chat history with the same token...
Facebook composition smoke passed successfully.
```

The smoke script also checks that:

- `/posts/feed` returns `401` without a token.
- `/chat/chats` returns `401` without a token.
- `/bookings/venues` returns `404`, proving turf is not mounted.
- The same Alice JWT creates a post, creates a chat, sends a WebSocket chat
  message, and reads chat history.

## Zero Service Diff Proof

```bash
git diff --stat -- auth-service tweeter-service whatsapp-service
```

Verified output: no changes.

## Cleanup

```bash
docker compose -p facebook --profile tweeter --profile chat down -v
```

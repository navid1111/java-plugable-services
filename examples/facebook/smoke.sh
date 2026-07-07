#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
WS_BASE="${WS_KONG_URL:-ws://localhost:18000}"
STAMP="$(date +%s)"
ALICE="facebook_alice_${STAMP}"
BOB="facebook_bob_${STAMP}"
PASS="smoke_pass"

echo "Running facebook composition smoke against ${BASE}"

register() {
  local username="$1"
  curl -fsS -X POST "${BASE}/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${username}\",\"password\":\"${PASS}\"}" >/dev/null
}

login() {
  local username="$1"
  curl -fsS -X POST "${BASE}/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${username}\",\"password\":\"${PASS}\"}" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

require_contains() {
  local haystack="$1"
  local needle="$2"
  local label="$3"
  if ! printf '%s' "$haystack" | grep -F "$needle" >/dev/null; then
    echo "Expected ${label} to contain '${needle}'"
    echo "$haystack"
    exit 1
  fi
}

echo "[1/8] Confirming protected services reject missing tokens..."
POSTS_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/posts/feed")"
CHAT_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/chat/chats")"
BOOKINGS_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/bookings/venues")"
if [ "$POSTS_CODE" != "401" ] || [ "$CHAT_CODE" != "401" ]; then
  echo "Expected /posts and /chat to reject missing tokens; got posts=${POSTS_CODE}, chat=${CHAT_CODE}"
  exit 1
fi
if [ "$BOOKINGS_CODE" != "404" ]; then
  echo "Expected /bookings to be absent when turf profile is not enabled, got ${BOOKINGS_CODE}"
  exit 1
fi

echo "[2/8] Registering users..."
register "$ALICE"
register "$BOB"

echo "[3/8] Logging in once as Alice..."
ALICE_TOKEN="$(login "$ALICE")"
if [ -z "$ALICE_TOKEN" ]; then
  echo "Failed to get Alice token"
  exit 1
fi

echo "[4/8] Creating a post with Alice's token..."
POST_CONTENT="facebook composition post ${STAMP}"
POST_BODY="$(curl -fsS -X POST "${BASE}/posts" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"content\":\"${POST_CONTENT}\"}")"
require_contains "$POST_BODY" "$POST_CONTENT" "created post"

echo "[5/8] Reading posts with the same token..."
POSTS="$(curl -fsS "${BASE}/posts?author=${ALICE}" -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$POSTS" "$POST_CONTENT" "Alice posts"

echo "[6/8] Creating a chat with Bob using the same token..."
CHAT_BODY="$(curl -fsS -X POST "${BASE}/chat/chats" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"participants\":[\"${BOB}\"],\"name\":\"facebook smoke chat\"}")"
CHAT_ID="$(printf '%s' "$CHAT_BODY" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
if [ -z "$CHAT_ID" ]; then
  echo "Failed to parse chat id"
  echo "$CHAT_BODY"
  exit 1
fi

echo "[7/8] Sending a chat message over WebSocket with Alice's token..."
CHAT_CONTENT="facebook composition chat ${STAMP}"
node "$(dirname "$0")/ws-send.mjs" "${WS_BASE}/chat/ws" "$ALICE_TOKEN" "$CHAT_ID" "$CHAT_CONTENT"

echo "[8/8] Reading chat history with the same token..."
HISTORY="$(curl -fsS "${BASE}/chat/chats/${CHAT_ID}/messages" -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$HISTORY" "$CHAT_CONTENT" "chat history"

echo "Facebook composition smoke passed successfully."

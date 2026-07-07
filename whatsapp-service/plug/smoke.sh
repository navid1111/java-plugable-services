#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
WS_BASE="${WS_KONG_URL:-ws://localhost:18000}"
STAMP="$(date +%s)"
ALICE="chat_alice_${STAMP}"
BOB="chat_bob_${STAMP}"
CAROL="chat_carol_${STAMP}"
PASS="smoke_pass"

echo "Running chat smoke test against ${BASE}"

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

echo "[1/8] Registering users..."
register "$ALICE"
register "$BOB"
register "$CAROL"

echo "[2/8] Logging in..."
ALICE_TOKEN="$(login "$ALICE")"
BOB_TOKEN="$(login "$BOB")"
CAROL_TOKEN="$(login "$CAROL")"

if [ -z "$ALICE_TOKEN" ] || [ -z "$BOB_TOKEN" ] || [ -z "$CAROL_TOKEN" ]; then
  echo "Failed to get tokens"
  exit 1
fi

echo "[3/8] Verifying Kong rejects missing token..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/chat/chats")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /chat/chats, got ${HTTP_CODE}"
  exit 1
fi

echo "[4/8] Creating chat..."
CHAT_BODY="$(curl -fsS -X POST "${BASE}/chat/chats" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"participants\":[\"${BOB}\"],\"name\":\"smoke chat\"}")"
CHAT_ID="$(printf '%s' "$CHAT_BODY" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
if [ -z "$CHAT_ID" ]; then
  echo "Failed to parse chat id"
  echo "$CHAT_BODY"
  exit 1
fi

echo "[5/8] Listing chats..."
BOB_CHATS="$(curl -fsS "${BASE}/chat/chats" -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$BOB_CHATS" "$ALICE" "Bob chat list"
require_contains "$BOB_CHATS" "$BOB" "Bob chat list"

echo "[6/8] Verifying non-participant history is forbidden..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/chat/chats/${CHAT_ID}/messages" \
  -H "Authorization: Bearer ${CAROL_TOKEN}")"
if [ "$HTTP_CODE" != "403" ]; then
  echo "Expected 403 for non-participant history, got ${HTTP_CODE}"
  exit 1
fi

echo "[7/8] Running WebSocket live + offline flow..."
node "$(dirname "$0")/ws-smoke.mjs" "${WS_BASE}/chat/ws" "$ALICE_TOKEN" "$BOB_TOKEN" "$CHAT_ID"

echo "[8/8] Checking persisted history with cursor paging..."
HISTORY1="$(curl -fsS "${BASE}/chat/chats/${CHAT_ID}/messages?pageSize=2" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$HISTORY1" "offline three" "first history page"
CURSOR="$(printf '%s' "$HISTORY1" | sed -n 's/.*"nextCursor":"\([^"]*\)".*/\1/p')"
if [ -z "$CURSOR" ]; then
  echo "Expected nextCursor on first history page"
  echo "$HISTORY1"
  exit 1
fi
HISTORY2="$(curl -fsS "${BASE}/chat/chats/${CHAT_ID}/messages?pageSize=2&cursor=${CURSOR}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$HISTORY2" "live message" "second history page"

echo "Chat smoke test passed successfully."

#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
ALICE="alice_${STAMP}"
BOB="bob_${STAMP}"
PASS="smoke_pass"

echo "Running tweeter smoke test against ${BASE}"

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

post_as() {
  local token="$1"
  local content="$2"
  curl -fsS -X POST "${BASE}/posts" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"content\":\"${content}\"}"
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

echo "[2/8] Logging in..."
ALICE_TOKEN="$(login "$ALICE")"
BOB_TOKEN="$(login "$BOB")"

if [ -z "$ALICE_TOKEN" ] || [ -z "$BOB_TOKEN" ]; then
  echo "Failed to get tokens"
  exit 1
fi
BOB_ID="$(curl -fsS "${BASE}/auth/me" -H "Authorization: Bearer ${BOB_TOKEN}" \
  | sed -n 's/.*"userId":"\([^"]*\)".*/\1/p')"
if [ -z "$BOB_ID" ]; then
  echo "Failed to resolve Bob's public user id"
  exit 1
fi

echo "[3/8] Verifying Kong rejects missing token..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/posts/feed")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /posts/feed, got ${HTTP_CODE}"
  exit 1
fi

echo "[4/8] Alice follows Bob..."
curl -fsS -X PUT "${BASE}/posts/users/${BOB_ID}/follow?username=${BOB}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" >/dev/null

echo "[5/8] Bob creates posts..."
C1="first post ${STAMP}"
C2="second post ${STAMP}"
C3="third post ${STAMP}"
P1="$(post_as "$BOB_TOKEN" "$C1")"
post_as "$BOB_TOKEN" "$C2" >/dev/null
post_as "$BOB_TOKEN" "$C3" >/dev/null
POST_ID="$(printf '%s' "$P1" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"

if [ -z "$POST_ID" ]; then
  echo "Failed to parse created post id"
  echo "$P1"
  exit 1
fi

echo "[6/8] Reading post and author listing..."
POST_BODY="$(curl -fsS "${BASE}/posts/${POST_ID}" -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$POST_BODY" "$C1" "post lookup"

AUTHOR_POSTS="$(curl -fsS "${BASE}/posts?authorUserId=${BOB_ID}" -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$AUTHOR_POSTS" "$C3" "author listing"

echo "[7/8] Reading feed with cursor paging..."
FEED1="$(curl -fsS "${BASE}/posts/feed?pageSize=2" -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$FEED1" "$C3" "first feed page"
require_contains "$FEED1" "$C2" "first feed page"

CURSOR="$(printf '%s' "$FEED1" | sed -n 's/.*"nextCursor":"\([^"]*\)".*/\1/p')"
if [ -z "$CURSOR" ]; then
  echo "Expected nextCursor on first feed page"
  echo "$FEED1"
  exit 1
fi

FEED2="$(curl -fsS "${BASE}/posts/feed?pageSize=2&cursor=${CURSOR}" -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$FEED2" "$C1" "second feed page"

echo "[8/8] Unfollow removes Bob from Alice's feed..."
curl -fsS -X DELETE "${BASE}/posts/users/${BOB_ID}/follow" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" >/dev/null
EMPTY_FEED="$(curl -fsS "${BASE}/posts/feed" -H "Authorization: Bearer ${ALICE_TOKEN}")"
if printf '%s' "$EMPTY_FEED" | grep -F "$C3" >/dev/null; then
  echo "Expected feed to be empty after unfollow"
  echo "$EMPTY_FEED"
  exit 1
fi

echo "Tweeter smoke test passed successfully."

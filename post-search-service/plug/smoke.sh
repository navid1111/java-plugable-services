#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
ALICE="search_alice_${STAMP}"
BOB="search_bob_${STAMP}"
PASS="smoke_pass"

TARGET_TYPE="tweeter.post"
DOC_A="post_${STAMP}_alpha"
DOC_B="post_${STAMP}_beta"
DOC_C="post_${STAMP}_popular"

echo "Running post-search smoke test against ${BASE}"

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

index_document() {
  local token="$1"
  local target_id="$2"
  local author="$3"
  local content="$4"
  local created_at="$5"
  curl -fsS -X PUT "${BASE}/post-search/documents/${TARGET_TYPE}/${target_id}" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"authorUsername\":\"${author}\",\"content\":\"${content}\",\"createdAt\":\"${created_at}\"}"
}

update_likes() {
  local token="$1"
  local target_id="$2"
  local like_count="$3"
  curl -fsS -X PUT "${BASE}/post-search/documents/${TARGET_TYPE}/${target_id}/like-count" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"likeCount\":${like_count}}"
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

require_not_contains() {
  local haystack="$1"
  local needle="$2"
  local label="$3"
  if printf '%s' "$haystack" | grep -F "$needle" >/dev/null; then
    echo "Expected ${label} not to contain '${needle}'"
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

echo "[3/8] Verifying Kong rejects missing token..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/post-search?q=java")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /post-search, got ${HTTP_CODE}"
  exit 1
fi

echo "[4/8] Verifying blank queries fail..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/post-search?q=" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
if [ "$HTTP_CODE" != "400" ]; then
  echo "Expected 400 for blank q, got ${HTTP_CODE}"
  exit 1
fi

echo "[5/8] Indexing three post snapshots..."
CONTENT_A="java spring alpha ${STAMP}"
CONTENT_B="java docker beta ${STAMP}"
CONTENT_C="java spring popular ${STAMP}"
index_document "$ALICE_TOKEN" "$DOC_A" "$ALICE" "$CONTENT_A" "2026-07-09T00:00:01Z" >/dev/null
index_document "$BOB_TOKEN" "$DOC_B" "$BOB" "$CONTENT_B" "2026-07-09T00:00:02Z" >/dev/null
index_document "$BOB_TOKEN" "$DOC_C" "$BOB" "$CONTENT_C" "2026-07-09T00:00:03Z" >/dev/null

echo "[6/8] Updating like count on the popular document..."
update_likes "$BOB_TOKEN" "$DOC_C" 77 >/dev/null

echo "[7/8] Searching by keyword with recency order..."
RECENCY="$(curl -fsS "${BASE}/post-search?q=java%20spring&sort=recency&pageSize=2" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$RECENCY" "$CONTENT_C" "recency search"
require_contains "$RECENCY" "$CONTENT_A" "recency search"
require_not_contains "$RECENCY" "$CONTENT_B" "recency search"
case "$RECENCY" in
  *"$CONTENT_C"*"$CONTENT_A"*) ;;
  *)
    echo "Expected newest spring document before older spring document"
    echo "$RECENCY"
    exit 1
    ;;
esac

echo "[8/8] Searching by keyword with like order..."
LIKES="$(curl -fsS "${BASE}/post-search?q=java%20spring&sort=likes&pageSize=1" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$LIKES" "$CONTENT_C" "likes search"
require_not_contains "$LIKES" "$CONTENT_A" "likes search"

echo "Post-search smoke test passed successfully."

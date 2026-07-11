#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
ALICE="combo_alice_${STAMP}"
BOB="combo_bob_${STAMP}"
PASS="smoke_pass"
TARGET_TYPE="tweeter.post"

echo "Running auth + post + comment + post-search smoke test against ${BASE}"

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

create_post() {
  local token="$1"
  local content="$2"
  curl -fsS -X POST "${BASE}/posts" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"content\":\"${content}\"}"
}

create_comment() {
  local token="$1"
  local target_id="$2"
  local content="$3"
  curl -fsS -X POST "${BASE}/comments/targets/${TARGET_TYPE}/${target_id}" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"content\":\"${content}\"}"
}

# Search is populated by post.created.v1 events (event-driven projection), not by the
# client. Poll the authoritative search endpoint until the projection has caught up.
wait_for_search() {
  local token="$1"
  local query="$2"
  local needle="$3"
  local label="$4"
  local deadline=$(( $(date +%s) + 30 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local res
    res="$(curl -fsS "${BASE}/post-search?q=${query}&pageSize=10" \
      -H "Authorization: Bearer ${token}" || true)"
    if printf '%s' "$res" | grep -F "$needle" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for ${label} to appear in event-indexed search"
  exit 1
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

parse_number_field() {
  local json="$1"
  local field="$2"
  printf '%s' "$json" | sed -n "s/.*\"${field}\":\\([0-9][0-9]*\\).*/\\1/p"
}

parse_string_field() {
  local json="$1"
  local field="$2"
  printf '%s' "$json" | sed -n "s/.*\"${field}\":\"\\([^\"]*\\)\".*/\\1/p"
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

echo "[3/8] Verifying Kong rejects unauthenticated search..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/post-search?q=java")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /post-search, got ${HTTP_CODE}"
  exit 1
fi

echo "[4/8] Creating two posts through tweeter-service..."
LOW_CONTENT="java search low ${STAMP}"
HIGH_CONTENT="java search high ${STAMP}"
LOW_POST="$(create_post "$ALICE_TOKEN" "$LOW_CONTENT")"
sleep 1
HIGH_POST="$(create_post "$BOB_TOKEN" "$HIGH_CONTENT")"

LOW_POST_ID="$(parse_number_field "$LOW_POST" "id")"
HIGH_POST_ID="$(parse_number_field "$HIGH_POST" "id")"
LOW_CREATED_AT="$(parse_string_field "$LOW_POST" "createdAt")"
HIGH_CREATED_AT="$(parse_string_field "$HIGH_POST" "createdAt")"

if [ -z "$LOW_POST_ID" ] || [ -z "$HIGH_POST_ID" ] || [ -z "$LOW_CREATED_AT" ] || [ -z "$HIGH_CREATED_AT" ]; then
  echo "Failed to parse post responses"
  echo "$LOW_POST"
  echo "$HIGH_POST"
  exit 1
fi

echo "[5/8] Commenting on the high-ranked post through comment-service..."
COMMENT_CONTENT="comment on searchable post ${STAMP}"
COMMENT_BODY="$(create_comment "$ALICE_TOKEN" "$HIGH_POST_ID" "$COMMENT_CONTENT")"
require_contains "$COMMENT_BODY" "$COMMENT_CONTENT" "created comment"
require_contains "$COMMENT_BODY" "$TARGET_TYPE" "created comment"

COMMENTS="$(curl -fsS "${BASE}/comments/targets/${TARGET_TYPE}/${HIGH_POST_ID}" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$COMMENTS" "$COMMENT_CONTENT" "post comments"

echo "[6/8] Waiting for the event-driven search projection to index both posts..."
wait_for_search "$ALICE_TOKEN" "java%20search%20${STAMP}" "$LOW_CONTENT" "low post"
wait_for_search "$ALICE_TOKEN" "java%20search%20${STAMP}" "$HIGH_CONTENT" "high post"

echo "[7/8] Searching by recency..."
RECENCY="$(curl -fsS "${BASE}/post-search?q=java%20search%20${STAMP}&sort=recency&pageSize=2" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$RECENCY" "$HIGH_CONTENT" "recency search"
require_contains "$RECENCY" "$LOW_CONTENT" "recency search"
case "$RECENCY" in
  *"$HIGH_CONTENT"*"$LOW_CONTENT"*) ;;
  *)
    echo "Expected newer post before older post in recency search"
    echo "$RECENCY"
    exit 1
    ;;
esac

echo "[8/8] Searching by likes (equal like counts fall back to recency order)..."
LIKES="$(curl -fsS "${BASE}/post-search?q=java%20search%20${STAMP}&sort=likes&pageSize=2" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$LIKES" "$HIGH_CONTENT" "likes search"
require_contains "$LIKES" "$LOW_CONTENT" "likes search"
case "$LIKES" in
  *"$HIGH_CONTENT"*"$LOW_CONTENT"*) ;;
  *)
    echo "Expected higher-like post before lower-like post in likes search"
    echo "$LIKES"
    exit 1
    ;;
esac

echo "Auth + post + comment + post-search smoke test passed successfully."

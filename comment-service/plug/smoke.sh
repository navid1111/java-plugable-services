#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
ALICE="comment_alice_${STAMP}"
BOB="comment_bob_${STAMP}"
PASS="smoke_pass"

TARGET_TYPE="post"

echo "Running comment smoke test against ${BASE}"

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

comment_as() {
  local token="$1"
  local target_type="$2"
  local target_id="$3"
  local content="$4"
  local response body status attempt
  for attempt in 1 2 3; do
    response="$(curl -sS -X POST "${BASE}/comments/targets/${target_type}/${target_id}" \
      -H "Authorization: Bearer ${token}" \
      -H 'Content-Type: application/json' \
      -d "{\"content\":\"${content}\"}" \
      -w $'\n%{http_code}')"
    status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [ "$status" = "201" ]; then
      printf '%s' "$body"
      return 0
    fi
    if [ "$status" = "400" ] && printf '%s' "$body" | grep -F "target does not exist or is deleted" >/dev/null \
        && [ "$attempt" -lt 3 ]; then
      echo "Waiting for comment target projection (${attempt}/3)..." >&2
      sleep 1
      continue
    fi
    echo "Comment creation failed with HTTP ${status}: ${body}" >&2
    return 1
  done
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

echo "[3/8] Creating two real post targets..."
POST_A="$(post_as "$BOB_TOKEN" "comment smoke target A ${STAMP}")"
POST_B="$(post_as "$ALICE_TOKEN" "comment smoke target B ${STAMP}")"
TARGET_A_ID="$(printf '%s' "$POST_A" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
TARGET_B_ID="$(printf '%s' "$POST_B" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
if [ -z "$TARGET_A_ID" ] || [ -z "$TARGET_B_ID" ]; then
  echo "Failed to create real post targets"
  echo "$POST_A"
  echo "$POST_B"
  exit 1
fi

echo "[4/8] Verifying Kong rejects missing token..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" \
  "${BASE}/comments/targets/${TARGET_TYPE}/${TARGET_A_ID}")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /comments, got ${HTTP_CODE}"
  exit 1
fi

echo "[5/8] Creating comments on two real targets..."
C1="oldest target-a comment ${STAMP}"
C2="newest target-a comment ${STAMP}"
OTHER="target-b comment ${STAMP}"

FIRST_COMMENT="$(comment_as "$BOB_TOKEN" "$TARGET_TYPE" "$TARGET_A_ID" "$C1")"
COMMENT_ID="$(printf '%s' "$FIRST_COMMENT" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"

if [ -z "$COMMENT_ID" ]; then
  echo "Failed to parse created comment id"
  echo "$FIRST_COMMENT"
  exit 1
fi

comment_as "$BOB_TOKEN" "$TARGET_TYPE" "$TARGET_A_ID" "$C2" >/dev/null
comment_as "$ALICE_TOKEN" "$TARGET_TYPE" "$TARGET_B_ID" "$OTHER" >/dev/null

echo "[6/8] Reading target comments with cursor paging and isolation..."
PAGE1="$(curl -fsS "${BASE}/comments/targets/${TARGET_TYPE}/${TARGET_A_ID}?pageSize=1" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$PAGE1" "$C2" "first comment page"
require_not_contains "$PAGE1" "$C1" "first comment page"
require_not_contains "$PAGE1" "$OTHER" "target-a page"

CURSOR="$(printf '%s' "$PAGE1" | sed -n 's/.*"nextCursor":"\([^"]*\)".*/\1/p')"
if [ -z "$CURSOR" ]; then
  echo "Expected nextCursor on first comment page"
  echo "$PAGE1"
  exit 1
fi

PAGE2="$(curl -fsS "${BASE}/comments/targets/${TARGET_TYPE}/${TARGET_A_ID}?pageSize=1&cursor=${CURSOR}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$PAGE2" "$C1" "second comment page"
require_not_contains "$PAGE2" "$OTHER" "second target-a page"

echo "[7/8] Verifying non-owner cannot delete..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
  "${BASE}/comments/${COMMENT_ID}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
if [ "$HTTP_CODE" != "403" ]; then
  echo "Expected 403 for non-owner delete, got ${HTTP_CODE}"
  exit 1
fi

echo "[8/8] Owner deletes their comment..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
  "${BASE}/comments/${COMMENT_ID}" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 for owner delete, got ${HTTP_CODE}"
  exit 1
fi

echo "Comment smoke test passed successfully."

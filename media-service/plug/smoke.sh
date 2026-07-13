#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
ALICE="media_alice_${STAMP}"
BOB="media_bob_${STAMP}"
PASS="smoke_pass"
TARGET_TYPE="post"
IMAGE_FILE="/tmp/media-smoke-${STAMP}.png"

echo "Running media smoke test against ${BASE}"

cleanup_local() {
  rm -f "$IMAGE_FILE"
}
trap cleanup_local EXIT

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

upload_media() {
  local token="$1"
  local target_type="$2"
  local target_id="$3"
  local path="$4"
  local mime_type="$5"
  local caption="$6"
  local response body status attempt
  for attempt in 1 2; do
    response="$(curl -sS -X POST "${BASE}/media/targets/${target_type}/${target_id}" \
      -H "Authorization: Bearer ${token}" \
      -F "file=@${path};type=${mime_type}" \
      -F "caption=${caption}" \
      -F "altText=media smoke asset ${STAMP}" \
      -w $'\n%{http_code}')"
    status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [ "$status" = "201" ]; then
      printf '%s' "$body"
      return 0
    fi
    if { [ "$status" = "400" ] || [ "$status" = "403" ]; } \
        && printf '%s' "$body" | grep -F "target does not exist or is deleted" >/dev/null \
        && [ "$attempt" -lt 2 ]; then
      echo "Waiting for media target projection (${attempt}/2)..." >&2
      sleep 1
      continue
    fi
    echo "Media upload failed with HTTP ${status}: ${body}" >&2
    return 1
  done
}

post_as() {
  local token="$1"
  local content="$2"
  curl -fsS -X POST "${BASE}/posts" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"content\":\"${content}\"}"
}

delete_media() {
  local token="$1"
  local media_id="$2"
  curl -s -o /dev/null -w "%{http_code}" -X DELETE "${BASE}/media/${media_id}" \
    -H "Authorization: Bearer ${token}"
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

parse_number_field() {
  local json="$1"
  local field="$2"
  printf '%s' "$json" | sed -n "s/.*\"${field}\":\\([0-9][0-9]*\\).*/\\1/p"
}

echo "[1/10] Preparing tiny image fixture..."
printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=' \
  | base64 -d > "$IMAGE_FILE"

echo "[2/10] Registering users..."
register "$ALICE"
register "$BOB"

echo "[3/10] Logging in..."
ALICE_TOKEN="$(login "$ALICE")"
BOB_TOKEN="$(login "$BOB")"

if [ -z "$ALICE_TOKEN" ] || [ -z "$BOB_TOKEN" ]; then
  echo "Failed to get tokens"
  exit 1
fi

echo "[4/10] Creating two real post targets..."
POST_A="$(post_as "$ALICE_TOKEN" "media smoke target A ${STAMP}")"
POST_B="$(post_as "$ALICE_TOKEN" "media smoke target B ${STAMP}")"
TARGET_A_ID="$(parse_number_field "$POST_A" "id")"
TARGET_B_ID="$(parse_number_field "$POST_B" "id")"
if [ -z "$TARGET_A_ID" ] || [ -z "$TARGET_B_ID" ]; then
  echo "Failed to create real post targets"
  echo "$POST_A"
  echo "$POST_B"
  exit 1
fi

echo "[5/10] Verifying Kong rejects missing token..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" \
  "${BASE}/media/targets/${TARGET_TYPE}/${TARGET_A_ID}")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /media, got ${HTTP_CODE}"
  exit 1
fi

echo "[6/10] Uploading media to two real post targets..."
FIRST_CAPTION="first post media ${STAMP}"
SECOND_CAPTION="second post media ${STAMP}"
FIRST_MEDIA="$(upload_media "$ALICE_TOKEN" "$TARGET_TYPE" "$TARGET_A_ID" "$IMAGE_FILE" "image/png" "$FIRST_CAPTION")"
SECOND_MEDIA="$(upload_media "$ALICE_TOKEN" "$TARGET_TYPE" "$TARGET_B_ID" "$IMAGE_FILE" "image/png" "$SECOND_CAPTION")"

FIRST_MEDIA_ID="$(parse_number_field "$FIRST_MEDIA" "id")"
SECOND_MEDIA_ID="$(parse_number_field "$SECOND_MEDIA" "id")"

if [ -z "$FIRST_MEDIA_ID" ] || [ -z "$SECOND_MEDIA_ID" ]; then
  echo "Failed to parse uploaded media ids"
  echo "$FIRST_MEDIA"
  echo "$SECOND_MEDIA"
  exit 1
fi

require_contains "$FIRST_MEDIA" "$FIRST_CAPTION" "first post upload"
require_contains "$FIRST_MEDIA" "$TARGET_TYPE" "first post upload"
require_contains "$SECOND_MEDIA" "$SECOND_CAPTION" "second post upload"
require_contains "$SECOND_MEDIA" "$TARGET_TYPE" "second post upload"

echo "[7/10] Listing media by target with isolation..."
FIRST_LIST="$(curl -fsS "${BASE}/media/targets/${TARGET_TYPE}/${TARGET_A_ID}?pageSize=2" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$FIRST_LIST" "$FIRST_CAPTION" "first post media list"
require_not_contains "$FIRST_LIST" "$SECOND_CAPTION" "first post media list"

SECOND_LIST="$(curl -fsS "${BASE}/media/targets/${TARGET_TYPE}/${TARGET_B_ID}?pageSize=2" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$SECOND_LIST" "$SECOND_CAPTION" "second post media list"
require_not_contains "$SECOND_LIST" "$FIRST_CAPTION" "second post media list"

echo "[8/10] Reading uploaded media by id..."
MEDIA_BODY="$(curl -fsS "${BASE}/media/${FIRST_MEDIA_ID}" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$MEDIA_BODY" "$FIRST_CAPTION" "media lookup"
require_contains "$MEDIA_BODY" "secureUrl" "media lookup"

echo "[9/10] Verifying non-owner cannot delete..."
HTTP_CODE="$(delete_media "$BOB_TOKEN" "$FIRST_MEDIA_ID")"
if [ "$HTTP_CODE" != "403" ]; then
  echo "Expected 403 for non-owner delete, got ${HTTP_CODE}"
  exit 1
fi

echo "[10/10] Owner deletes uploaded media..."
HTTP_CODE="$(delete_media "$ALICE_TOKEN" "$FIRST_MEDIA_ID")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 for owner delete on post media, got ${HTTP_CODE}"
  exit 1
fi
HTTP_CODE="$(delete_media "$ALICE_TOKEN" "$SECOND_MEDIA_ID")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 for owner delete on second post media, got ${HTTP_CODE}"
  exit 1
fi

echo "Media smoke test passed successfully."

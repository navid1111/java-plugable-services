#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
ALICE="media_alice_${STAMP}"
BOB="media_bob_${STAMP}"
PASS="smoke_pass"
POST_TARGET_TYPE="tweeter.post"
POST_TARGET_ID="post_${STAMP}"
COMMENT_TARGET_TYPE="comment.comment"
COMMENT_TARGET_ID="comment_${STAMP}"
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
  curl -fsS -X POST "${BASE}/media/targets/${target_type}/${target_id}" \
    -H "Authorization: Bearer ${token}" \
    -F "file=@${path};type=${mime_type}" \
    -F "caption=${caption}" \
    -F "altText=media smoke asset ${STAMP}"
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

echo "[1/9] Preparing tiny image fixture..."
printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=' \
  | base64 -d > "$IMAGE_FILE"

echo "[2/9] Registering users..."
register "$ALICE"
register "$BOB"

echo "[3/9] Logging in..."
ALICE_TOKEN="$(login "$ALICE")"
BOB_TOKEN="$(login "$BOB")"

if [ -z "$ALICE_TOKEN" ] || [ -z "$BOB_TOKEN" ]; then
  echo "Failed to get tokens"
  exit 1
fi

echo "[4/9] Verifying Kong rejects missing token..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" \
  "${BASE}/media/targets/${POST_TARGET_TYPE}/${POST_TARGET_ID}")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /media, got ${HTTP_CODE}"
  exit 1
fi

echo "[5/9] Uploading media to post and comment target references..."
POST_CAPTION="post media ${STAMP}"
COMMENT_CAPTION="comment media ${STAMP}"
POST_MEDIA="$(upload_media "$ALICE_TOKEN" "$POST_TARGET_TYPE" "$POST_TARGET_ID" "$IMAGE_FILE" "image/png" "$POST_CAPTION")"
COMMENT_MEDIA="$(upload_media "$ALICE_TOKEN" "$COMMENT_TARGET_TYPE" "$COMMENT_TARGET_ID" "$IMAGE_FILE" "image/png" "$COMMENT_CAPTION")"

POST_MEDIA_ID="$(parse_number_field "$POST_MEDIA" "id")"
COMMENT_MEDIA_ID="$(parse_number_field "$COMMENT_MEDIA" "id")"

if [ -z "$POST_MEDIA_ID" ] || [ -z "$COMMENT_MEDIA_ID" ]; then
  echo "Failed to parse uploaded media ids"
  echo "$POST_MEDIA"
  echo "$COMMENT_MEDIA"
  exit 1
fi

require_contains "$POST_MEDIA" "$POST_CAPTION" "post upload"
require_contains "$POST_MEDIA" "$POST_TARGET_TYPE" "post upload"
require_contains "$COMMENT_MEDIA" "$COMMENT_CAPTION" "comment upload"
require_contains "$COMMENT_MEDIA" "$COMMENT_TARGET_TYPE" "comment upload"

echo "[6/9] Listing media by target with isolation..."
POST_LIST="$(curl -fsS "${BASE}/media/targets/${POST_TARGET_TYPE}/${POST_TARGET_ID}?pageSize=2" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$POST_LIST" "$POST_CAPTION" "post media list"
require_not_contains "$POST_LIST" "$COMMENT_CAPTION" "post media list"

COMMENT_LIST="$(curl -fsS "${BASE}/media/targets/${COMMENT_TARGET_TYPE}/${COMMENT_TARGET_ID}?pageSize=2" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$COMMENT_LIST" "$COMMENT_CAPTION" "comment media list"
require_not_contains "$COMMENT_LIST" "$POST_CAPTION" "comment media list"

echo "[7/9] Reading uploaded media by id..."
MEDIA_BODY="$(curl -fsS "${BASE}/media/${POST_MEDIA_ID}" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$MEDIA_BODY" "$POST_CAPTION" "media lookup"
require_contains "$MEDIA_BODY" "secureUrl" "media lookup"

echo "[8/9] Verifying non-owner cannot delete..."
HTTP_CODE="$(delete_media "$BOB_TOKEN" "$POST_MEDIA_ID")"
if [ "$HTTP_CODE" != "403" ]; then
  echo "Expected 403 for non-owner delete, got ${HTTP_CODE}"
  exit 1
fi

echo "[9/9] Owner deletes uploaded media..."
HTTP_CODE="$(delete_media "$ALICE_TOKEN" "$POST_MEDIA_ID")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 for owner delete on post media, got ${HTTP_CODE}"
  exit 1
fi
HTTP_CODE="$(delete_media "$ALICE_TOKEN" "$COMMENT_MEDIA_ID")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 for owner delete on comment media, got ${HTTP_CODE}"
  exit 1
fi

echo "Media smoke test passed successfully."

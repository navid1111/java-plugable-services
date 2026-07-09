#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
ALICE="media_combo_alice_${STAMP}"
BOB="media_combo_bob_${STAMP}"
PASS="smoke_pass"
POST_TARGET_TYPE="tweeter.post"
COMMENT_TARGET_TYPE="comment.comment"
IMAGE_FILE="/tmp/media-combo-${STAMP}.png"

echo "Running auth + post + comment + post-search + media smoke test against ${BASE}"

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
  local post_id="$2"
  local content="$3"
  curl -fsS -X POST "${BASE}/comments/targets/${POST_TARGET_TYPE}/${post_id}" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"content\":\"${content}\"}"
}

index_post() {
  local token="$1"
  local post_id="$2"
  local author="$3"
  local content="$4"
  local created_at="$5"
  curl -fsS -X PUT "${BASE}/post-search/documents/${POST_TARGET_TYPE}/${post_id}" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"authorUsername\":\"${author}\",\"content\":\"${content}\",\"createdAt\":\"${created_at}\"}"
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
    -F "altText=media combo asset ${STAMP}"
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

echo "[1/11] Preparing tiny image fixture..."
printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=' \
  | base64 -d > "$IMAGE_FILE"

echo "[2/11] Registering users..."
register "$ALICE"
register "$BOB"

echo "[3/11] Logging in..."
ALICE_TOKEN="$(login "$ALICE")"
BOB_TOKEN="$(login "$BOB")"

if [ -z "$ALICE_TOKEN" ] || [ -z "$BOB_TOKEN" ]; then
  echo "Failed to get tokens"
  exit 1
fi

echo "[4/11] Verifying Kong rejects unauthenticated media and search..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/media/targets/${POST_TARGET_TYPE}/missing")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /media, got ${HTTP_CODE}"
  exit 1
fi
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/post-search?q=java")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /post-search, got ${HTTP_CODE}"
  exit 1
fi

echo "[5/11] Creating a real post through tweeter-service..."
POST_CONTENT="java media searchable post ${STAMP}"
POST_BODY="$(create_post "$ALICE_TOKEN" "$POST_CONTENT")"
POST_ID="$(parse_number_field "$POST_BODY" "id")"
POST_CREATED_AT="$(parse_string_field "$POST_BODY" "createdAt")"

if [ -z "$POST_ID" ] || [ -z "$POST_CREATED_AT" ]; then
  echo "Failed to parse post response"
  echo "$POST_BODY"
  exit 1
fi

echo "[6/11] Commenting on the post through comment-service..."
COMMENT_CONTENT="comment with media target ${STAMP}"
COMMENT_BODY="$(create_comment "$BOB_TOKEN" "$POST_ID" "$COMMENT_CONTENT")"
COMMENT_ID="$(parse_number_field "$COMMENT_BODY" "id")"

if [ -z "$COMMENT_ID" ]; then
  echo "Failed to parse comment response"
  echo "$COMMENT_BODY"
  exit 1
fi
require_contains "$COMMENT_BODY" "$COMMENT_CONTENT" "created comment"

echo "[7/11] Indexing the post into post-search-service..."
index_post "$ALICE_TOKEN" "$POST_ID" "$ALICE" "$POST_CONTENT" "$POST_CREATED_AT" >/dev/null

echo "[8/11] Uploading media to the post and the comment..."
POST_MEDIA_CAPTION="post media ${STAMP}"
COMMENT_MEDIA_CAPTION="comment media ${STAMP}"
POST_MEDIA="$(upload_media "$ALICE_TOKEN" "$POST_TARGET_TYPE" "$POST_ID" "$IMAGE_FILE" "image/png" "$POST_MEDIA_CAPTION")"
COMMENT_MEDIA="$(upload_media "$BOB_TOKEN" "$COMMENT_TARGET_TYPE" "$COMMENT_ID" "$IMAGE_FILE" "image/png" "$COMMENT_MEDIA_CAPTION")"
POST_MEDIA_ID="$(parse_number_field "$POST_MEDIA" "id")"
COMMENT_MEDIA_ID="$(parse_number_field "$COMMENT_MEDIA" "id")"

if [ -z "$POST_MEDIA_ID" ] || [ -z "$COMMENT_MEDIA_ID" ]; then
  echo "Failed to parse media responses"
  echo "$POST_MEDIA"
  echo "$COMMENT_MEDIA"
  exit 1
fi

require_contains "$POST_MEDIA" "$POST_MEDIA_CAPTION" "post media upload"
require_contains "$COMMENT_MEDIA" "$COMMENT_MEDIA_CAPTION" "comment media upload"

echo "[9/11] Listing media on both target namespaces..."
POST_MEDIA_LIST="$(curl -fsS "${BASE}/media/targets/${POST_TARGET_TYPE}/${POST_ID}" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$POST_MEDIA_LIST" "$POST_MEDIA_CAPTION" "post media list"

COMMENT_MEDIA_LIST="$(curl -fsS "${BASE}/media/targets/${COMMENT_TARGET_TYPE}/${COMMENT_ID}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$COMMENT_MEDIA_LIST" "$COMMENT_MEDIA_CAPTION" "comment media list"

echo "[10/11] Searching for the indexed post..."
SEARCH_RESULT="$(curl -fsS "${BASE}/post-search?q=java%20media%20${STAMP}&sort=recency&pageSize=2" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
require_contains "$SEARCH_RESULT" "$POST_CONTENT" "post search"
require_contains "$SEARCH_RESULT" "\"targetId\":\"${POST_ID}\"" "post search"

echo "[11/11] Deleting uploaded Cloudinary assets..."
HTTP_CODE="$(delete_media "$ALICE_TOKEN" "$POST_MEDIA_ID")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 for owner delete on post media, got ${HTTP_CODE}"
  exit 1
fi
HTTP_CODE="$(delete_media "$BOB_TOKEN" "$COMMENT_MEDIA_ID")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 for owner delete on comment media, got ${HTTP_CODE}"
  exit 1
fi

echo "Auth + post + comment + post-search + media smoke test passed successfully."

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BASE="${KONG_URL:-http://localhost:28000}"
ADMIN="${KONG_ADMIN_URL:-http://localhost:28001}"
CLEAN=false
KEEP=false
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=true ;;
    --keep) KEEP=true ;;
    *) echo "Usage: $0 [--clean] [--keep]"; exit 2 ;;
  esac
done

COMPOSE=(docker compose --project-name professional-messaging-e2e
  -f docker-compose.yml -f platform/post-lifecycle-e2e.compose.yml
  --profile tweeter --profile comments --profile post-search --profile media --profile bff)

cleanup() {
  if [ "$KEEP" = false ] && [ "$CLEAN" = true ]; then
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [ "$CLEAN" = true ]; then
  # This is an isolated Compose project; its volumes and network never overlap the dev stack.
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
fi

echo "Building and starting the post-lifecycle stack..."
"${COMPOSE[@]}" up -d --build --wait
# A retained Kong container can cache Docker DNS entries across application-container
# recreation. Restart it after the build so every upstream name resolves to the
# containers from this run; setup scripts below already wait for its Admin API.
"${COMPOSE[@]}" restart kong >/dev/null

KONG_ADMIN_URL="$ADMIN" ./kong/setup-core.sh
KONG_ADMIN_URL="$ADMIN" ./kong/setup-tweeter.sh
KONG_ADMIN_URL="$ADMIN" ./kong/setup-comments.sh
KONG_ADMIN_URL="$ADMIN" ./kong/setup-post-search.sh
KONG_ADMIN_URL="$ADMIN" ./kong/setup-media.sh
KONG_ADMIN_URL="$ADMIN" ./kong/setup-bff.sh

await_kong_route() {
  local path="$1" deadline=$((SECONDS + 30)) body
  while [ "$SECONDS" -lt "$deadline" ]; do
    body="$(curl -sS "$BASE${path}/__route_ready" 2>/dev/null || true)"
    if ! printf '%s' "$body" | grep -F 'no Route matched' >/dev/null; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for Kong route ${path} to propagate" >&2
  return 1
}

for prefix in /auth /posts /comments /post-search /media /bff; do
  await_kong_route "$prefix"
done
echo "Kong proxy routes are active."

STAMP="$(date +%s)"
USERNAME="lifecycle-${STAMP}"
PASSWORD="lifecycle-pass"
CONTENT="event convergence original ${STAMP}"
UPDATED="event convergence updated ${STAMP}"

json_number() { printf '%s' "$1" | sed -n "s/.*\"$2\":\([0-9][0-9]*\).*/\1/p"; }
json_string() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }

request_json() {
  local label="$1" method="$2" url="$3" expected="$4" data="${5:-}" token="${6:-}"
  local response status body
  local -a args=(-sS -X "$method" "$url" -H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer ${token}")
  [ -n "$data" ] && args+=(-d "$data")
  response="$(curl "${args[@]}" -w $'\n%{http_code}' || true)"
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [ "$status" != "$expected" ]; then
    echo "${label} expected HTTP ${expected}, got ${status}: ${body}" >&2
    return 1
  fi
  printf '%s' "$body"
}

await_contains() {
  local label="$1" url="$2" needle="$3" token="$4" deadline=$((SECONDS + 45)) body
  while [ "$SECONDS" -lt "$deadline" ]; do
    body="$(curl -fsS "$url" -H "Authorization: Bearer ${token}" 2>/dev/null || true)"
    if printf '%s' "$body" | grep -F "$needle" >/dev/null; then printf '%s' "$body"; return 0; fi
    sleep 1
  done
  echo "Timed out waiting for ${label}: ${url}" >&2
  return 1
}

await_status() {
  local label="$1" method="$2" url="$3" expected="$4" token="$5" data="${6:-}" deadline=$((SECONDS + 45)) status
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ -n "$data" ]; then
      status="$(curl -sS -o /dev/null -w '%{http_code}' -X "$method" "$url" \
        -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' -d "$data" || true)"
    else
      status="$(curl -sS -o /dev/null -w '%{http_code}' -X "$method" "$url" \
        -H "Authorization: Bearer ${token}" || true)"
    fi
    [ "$status" = "$expected" ] && return 0
    sleep 1
  done
  echo "Timed out waiting for ${label}; last HTTP status was ${status}" >&2
  return 1
}

request_json "register user" POST "$BASE/auth/register" 201 \
  "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" >/dev/null
echo "Registered lifecycle user."
LOGIN="$(request_json "login user" POST "$BASE/auth/login" 200 \
  "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")"
TOKEN="$(json_string "$LOGIN" access_token)"
[ -n "$TOKEN" ] || { echo "Login did not return an access token"; exit 1; }
echo "Authenticated lifecycle user."

POST="$(request_json "create post" POST "$BASE/posts" 201 \
  "{\"content\":\"${CONTENT}\"}" "$TOKEN")"
POST_ID="$(json_number "$POST" id)"
[ -n "$POST_ID" ] || { echo "Post response had no id: $POST"; exit 1; }
echo "Created post ${POST_ID}; waiting for consumer projections."

await_contains "created search projection" \
  "$BASE/post-search/documents/post/$POST_ID" "$CONTENT" "$TOKEN" >/dev/null
await_contains "comment target projection" \
  "$BASE/comments/targets/post/$POST_ID/summary" '"commentCount":0' "$TOKEN" >/dev/null
await_contains "media target projection" \
  "$BASE/media/targets/post/$POST_ID/summary" '"mediaCount":0' "$TOKEN" >/dev/null

INTENT="$(request_json "create media upload intent" POST "$BASE/media/upload-intents" 201 \
  "{\"targetType\":\"post\",\"targetId\":\"${POST_ID}\",\"idempotencyKey\":\"e2e-${STAMP}\",\"resourceType\":\"image\",\"format\":\"png\",\"bytes\":128}" "$TOKEN")"
INTENT_ID="$(json_string "$INTENT" id)"
PUBLIC_ID="$(json_string "$INTENT" publicId)"
[ -n "$INTENT_ID" ] && [ -n "$PUBLIC_ID" ] || {
  echo "Media intent response lacked id/publicId: $INTENT" >&2; exit 1;
}
MEDIA="$(request_json "finalize media upload" POST "$BASE/media/upload-intents/$INTENT_ID/finalize" 200 \
  "{\"publicId\":\"${PUBLIC_ID}\",\"resourceType\":\"image\",\"format\":\"png\",\"secureUrl\":\"https://res.cloudinary.com/e2e-cloud/image/upload/${PUBLIC_ID}.png\",\"bytes\":128,\"width\":16,\"height\":16,\"originalFilename\":\"e2e.png\"}" "$TOKEN")"
MEDIA_ID="$(json_number "$MEDIA" id)"
[ -n "$MEDIA_ID" ] || { echo "Media response had no id: $MEDIA" >&2; exit 1; }
await_contains "attached media projection" \
  "$BASE/media/targets/post/$POST_ID/summary" '"mediaCount":1' "$TOKEN" >/dev/null
await_contains "composed BFF detail" \
  "$BASE/bff/posts/$POST_ID" "$CONTENT" "$TOKEN" | grep -F '"degraded":[]' >/dev/null
echo "Create projections converged."

request_json "create comment" POST "$BASE/comments/targets/post/$POST_ID" 201 \
  "{\"content\":\"converged comment ${STAMP}\"}" "$TOKEN" >/dev/null
echo "Created comment; updating the owning post."

request_json "update post" PUT "$BASE/posts/$POST_ID" 200 \
  "{\"content\":\"${UPDATED}\",\"expectedVersion\":1}" "$TOKEN" >/dev/null
await_contains "updated search projection" \
  "$BASE/post-search/documents/post/$POST_ID" "$UPDATED" "$TOKEN" >/dev/null
await_contains "updated BFF detail" "$BASE/bff/posts/$POST_ID" "$UPDATED" "$TOKEN" >/dev/null
echo "Update projections converged; deleting the owning post."

request_json "delete post" DELETE "$BASE/posts/$POST_ID?expectedVersion=2" 204 "" "$TOKEN" >/dev/null
await_status "search tombstone" GET "$BASE/post-search/documents/post/$POST_ID" 404 "$TOKEN"
await_status "comment target tombstone" POST "$BASE/comments/targets/post/$POST_ID" 400 "$TOKEN" \
  '{"content":"must be rejected"}'
await_status "media target tombstone" GET "$BASE/media/targets/post/$POST_ID/summary" 400 "$TOKEN"
await_status "media asset cleanup" GET "$BASE/media/$MEDIA_ID" 404 "$TOKEN"
await_status "BFF post tombstone" GET "$BASE/bff/posts/$POST_ID" 410 "$TOKEN"

echo "Post create/update/delete convergence passed across search, comment, media, and BFF."

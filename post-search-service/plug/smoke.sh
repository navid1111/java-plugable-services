#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s%N)"
USER="search_user_${STAMP}"
PASS="smoke_pass"
CONTENT="event indexed class ${STAMP}"

echo "Running post-search smoke test against ${BASE}"

echo "[1/6] Registering and logging in..."
AUTH_BODY="$(printf '{\"username\":\"%s\",\"password\":\"%s\"}' "$USER" "$PASS")"
curl -fsS -X POST "${BASE}/auth/register" -H 'Content-Type: application/json' -d "$AUTH_BODY" >/dev/null
TOKEN="$(curl -fsS -X POST "${BASE}/auth/login" -H 'Content-Type: application/json' -d "$AUTH_BODY" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
test -n "$TOKEN"
AUTH=(-H "Authorization: Bearer ${TOKEN}")

echo "[2/6] Verifying authentication and query validation..."
CODE="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/post-search?q=java")"
test "$CODE" = 401 || { echo "Expected anonymous search to return 401, got $CODE"; exit 1; }
CODE="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/post-search?q=" "${AUTH[@]}")"
test "$CODE" = 400 || { echo "Expected blank search to return 400, got $CODE"; exit 1; }

echo "[3/6] Creating a post through its owning service..."
POST="$(curl -fsS -X POST "${BASE}/posts" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$(printf '{\"content\":\"%s\"}' "$CONTENT")")"
POST_ID="$(printf '%s' "$POST" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
test -n "$POST_ID"

echo "[4/6] Waiting for the lifecycle event to update search..."
FOUND=0
for _ in 1 2 3 4 5 6 7 8; do
  RESULT="$(curl -fsS "${BASE}/post-search?q=${STAMP}" "${AUTH[@]}")"
  if printf '%s' "$RESULT" | grep -F "\"targetId\":\"${POST_ID}\"" >/dev/null; then
    FOUND=1
    break
  fi
  sleep 0.35
done
test "$FOUND" = 1 || { echo "Post ${POST_ID} was not indexed from its lifecycle event"; exit 1; }

echo "[5/6] Reading the indexed projection..."
DOCUMENT="$(curl -fsS "${BASE}/post-search/documents/post/${POST_ID}" "${AUTH[@]}")"
printf '%s' "$DOCUMENT" | grep -F "$CONTENT" >/dev/null

echo "[6/6] Confirming removed public mutation endpoints stay unavailable..."
CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  "${BASE}/post-search/documents/post/${POST_ID}" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d '{}')"
test "$CODE" = 404 || { echo "Expected removed public mutation to return 404, got $CODE"; exit 1; }

echo "Post-search smoke test passed successfully."

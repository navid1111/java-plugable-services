#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

BASE="${GATEWAY_URL:-http://localhost:18000}"
ORIGIN="${APPBUILDER_ORIGIN:-http://localhost:8090}"
STAMP="$(date +%s%N)"
USER="appbuilder_${STAMP}"
PASS="appbuilder_pass"
AUTH_HEADER=()

json_field() {
  python3 -c 'import json,sys
value=json.load(sys.stdin)
for part in sys.argv[1].split("."):
    value=value.get(part) if isinstance(value,dict) else None
print("" if value is None else value)' "$1"
}

echo "[verify] Gateway: $BASE"

echo "[verify] Generated frontend contract lint"
python3 ./verify-frontend-contracts.py .

echo "[verify] CORS preflight for an authenticated media write"
PREFLIGHT="$(curl -isS -X OPTIONS "$BASE/media/upload-intents"   -H "Origin: $ORIGIN"   -H 'Access-Control-Request-Method: POST'   -H 'Access-Control-Request-Headers: Authorization,Content-Type,Idempotency-Key')"
printf '%s' "$PREFLIGHT" | grep -qi '^access-control-allow-origin:' || {
  echo "CORS preflight did not return Access-Control-Allow-Origin"
  printf '%s
' "$PREFLIGHT"
  exit 1
}

echo "[verify] Anonymous content call is rejected by JWT"
CODE="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/posts/feed")"
test "$CODE" = "401" || { echo "Expected 401 from anonymous /posts/feed, got $CODE"; exit 1; }

echo "[verify] Register and login"
AUTH_BODY="$(printf '{"username":"%s","password":"%s"}' "$USER" "$PASS")"
curl -fsS -X POST "$BASE/auth/register" -H 'Content-Type: application/json' -d "$AUTH_BODY" >/dev/null
LOGIN="$(curl -fsS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d "$AUTH_BODY")"
TOKEN="$(printf '%s' "$LOGIN" | json_field access_token)"
test -n "$TOKEN" || { echo "Login did not return access_token: $LOGIN"; exit 1; }
AUTH_HEADER=(-H "Authorization: Bearer $TOKEN")

echo "[verify] Authenticated identity"
ME="$(curl -fsS "$BASE/auth/me" "${AUTH_HEADER[@]}")"
USER_ID="$(printf '%s' "$ME" | json_field userId)"
test -n "$USER_ID" || { echo "GET /auth/me did not return userId: $ME"; exit 1; }

echo "[verify] Create post"
CONTENT="appbuilderverify${STAMP}"
POST="$(curl -fsS -X POST "$BASE/posts"   "${AUTH_HEADER[@]}"   -H 'Content-Type: application/json'   -d "$(printf '{"content":"%s"}' "$CONTENT")")"
POST_ID="$(printf '%s' "$POST" | json_field id)"
test -n "$POST_ID" || { echo "Could not parse post id: $POST"; exit 1; }

echo "[verify] BFF feed and detail composition"
BFF_DETAIL="$(curl -fsS "$BASE/bff/posts/$POST_ID" "${AUTH_HEADER[@]}")"
printf '%s' "$BFF_DETAIL" | python3 -c 'import json,sys
d=json.load(sys.stdin)
expected_id,expected_user=sys.argv[1],sys.argv[2]
assert str(d.get("post",{}).get("id")) == expected_id, d
assert d.get("author",{}).get("userId") == expected_user, d' "$POST_ID" "$USER_ID"
curl -fsS "$BASE/bff/feed" "${AUTH_HEADER[@]}" >/dev/null

echo "[verify] Event-driven target projections"
MEDIA_READY=0
for _ in 1 2 3 4 5 6; do
  CODE="$(curl -sS -o /dev/null -w '%{http_code}'     "$BASE/media/targets/post/$POST_ID/summary" "${AUTH_HEADER[@]}")"
  if [ "$CODE" = "200" ]; then MEDIA_READY=1; break; fi
  sleep 0.25
done
test "$MEDIA_READY" = "1" || { echo "Media target projection did not become ready"; exit 1; }

echo "[verify] Comment write"
COMMENT="$(curl -fsS -X POST "$BASE/comments/targets/post/$POST_ID"   "${AUTH_HEADER[@]}" -H 'Content-Type: application/json'   -d '{"content":"app builder backend verification"}')"
test -n "$(printf '%s' "$COMMENT" | json_field id)" || { echo "Comment response missing id: $COMMENT"; exit 1; }

echo "[verify] Media upload-intent contract"
INTENT_BODY="$(printf '{"targetType":"post","targetId":"%s","idempotencyKey":"verify-%s","resourceType":"image","format":"png","bytes":68}' "$POST_ID" "$STAMP")"
INTENT="$(curl -fsS -X POST "$BASE/media/upload-intents"   "${AUTH_HEADER[@]}" -H 'Content-Type: application/json' -d "$INTENT_BODY")"
INTENT_ID="$(printf '%s' "$INTENT" | json_field intent.id)"
UPLOAD_URL="$(printf '%s' "$INTENT" | json_field authorization.uploadUrl)"
test -n "$INTENT_ID" -a -n "$UPLOAD_URL" || { echo "Upload intent contract mismatch: $INTENT"; exit 1; }
curl -fsS -X POST "$BASE/media/upload-intents/$INTENT_ID/fail"   "${AUTH_HEADER[@]}" -H 'Content-Type: application/json'   -d '{"reasonCode":"verification_cleanup"}' >/dev/null

echo "[verify] Event-driven search projection"
SEARCH_FOUND=0
for _ in 1 2 3 4 5 6 7 8; do
  SEARCH="$(curl -fsS "$BASE/post-search?q=$CONTENT" "${AUTH_HEADER[@]}")"
  if printf '%s' "$SEARCH" | python3 -c 'import json,sys
d=json.load(sys.stdin); expected=sys.argv[1]
raise SystemExit(0 if any(str(x.get("targetId")) == expected for x in d.get("items",[])) else 1)' "$POST_ID"; then
    SEARCH_FOUND=1
    break
  fi
  sleep 0.25
done
test "$SEARCH_FOUND" = "1" || { echo "Post $POST_ID was not indexed from its lifecycle event"; exit 1; }

echo "[verify] Backend composition passed: auth + JWT + CORS + posts + BFF + comments + media intent + search events"

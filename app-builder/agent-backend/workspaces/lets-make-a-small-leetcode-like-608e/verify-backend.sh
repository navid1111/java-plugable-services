#!/usr/bin/env bash
set -euo pipefail

BASE="${GATEWAY_URL:-http://localhost:18000}"
ORIGIN="${APPBUILDER_ORIGIN:-http://localhost:8090}"
STAMP="$(date +%s)"
USER="appbuilder_${STAMP}"
PASS="appbuilder_pass"

echo "[verify] Gateway: $BASE"

echo "[verify] CORS preflight"
PREFLIGHT="$(curl -isS -X OPTIONS "$BASE/posts/feed"   -H "Origin: $ORIGIN"   -H 'Access-Control-Request-Method: GET'   -H 'Access-Control-Request-Headers: Authorization,Content-Type')"
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
TOKEN="$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))')"
test -n "$TOKEN" || { echo "Login did not return access_token: $LOGIN"; exit 1; }

echo "[verify] Authenticated content call"
curl -fsS "$BASE/posts/feed" -H "Authorization: Bearer $TOKEN" >/dev/null

echo "[verify] LeetCode CORS preflight"
LEETCODE_PREFLIGHT="$(curl -isS -X OPTIONS "$BASE/leetcode/problems" \
  -H "Origin: $ORIGIN" \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: Authorization,Content-Type,Idempotency-Key')"
printf '%s' "$LEETCODE_PREFLIGHT" | grep -qi '^access-control-allow-origin:' || {
  echo "LeetCode CORS preflight did not return Access-Control-Allow-Origin"
  printf '%s\n' "$LEETCODE_PREFLIGHT"
  exit 1
}
printf '%s' "$LEETCODE_PREFLIGHT" | grep -qi '^access-control-allow-headers:.*idempotency-key' || {
  echo "LeetCode CORS preflight does not allow Idempotency-Key"
  printf '%s\n' "$LEETCODE_PREFLIGHT"
  exit 1
}

echo "[verify] Load live LeetCode problems"
curl -fsS "$BASE/leetcode/problems" -H "Authorization: Bearer $TOKEN" >/dev/null

echo "[verify] Queue JavaScript submission"
SUBMISSION="$(curl -fsS -X POST "$BASE/leetcode/problems/two-sum/submit" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: verify-$STAMP" \
  -d '{"language":"javascript","code":"var twoSum = function(nums, target) { const seen = new Map(); for (let i = 0; i < nums.length; i++) { const need = target - nums[i]; if (seen.has(need)) return [seen.get(need), i]; seen.set(nums[i], i); } return []; };"}')"
SUBMISSION_ID="$(printf '%s' "$SUBMISSION" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id", ""))')"
test -n "$SUBMISSION_ID" || { echo "Submission did not return id: $SUBMISSION"; exit 1; }

echo "[verify] Poll asynchronous verdict"
VERDICT=""
for _ in $(seq 1 40); do
  RESULT="$(curl -fsS "$BASE/leetcode/submissions/$SUBMISSION_ID" -H "Authorization: Bearer $TOKEN")"
  VERDICT="$(printf '%s' "$RESULT" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))')"
  case "$VERDICT" in
    QUEUED|RUNNING) sleep 0.5 ;;
    *) break ;;
  esac
done
test "$VERDICT" = "ACCEPTED" || { echo "Expected ACCEPTED, got $VERDICT: $RESULT"; exit 1; }

echo "[verify] Create post"
POST="$(curl -fsS -X POST "$BASE/posts"   -H "Authorization: Bearer $TOKEN"   -H 'Content-Type: application/json'   -d "$(printf '{"content":"app-builder verify %s"}' "$STAMP")")"
POST_ID="$(printf '%s' "$POST" | sed -n 's/.*"id":\([0-9][0-9]*\).*//p')"
test -n "$POST_ID" || { echo "Could not parse post id: $POST"; exit 1; }

echo "[verify] Backend smoke passed: auth + JWT + CORS + posts + async LeetCode judge via Kong"

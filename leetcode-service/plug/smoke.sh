#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
ADMIN_USERNAME="${AUTH_ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${AUTH_ADMIN_PASSWORD:-change-admin-password}"
STAMP="$(date +%s)"
USER="leetcode_user_${STAMP}"
PASS="smoke_pass"
PROBLEM_ID="smoke-sum-${STAMP}"

login() {
  curl -fsS -X POST "${BASE}/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

echo "[1/6] Login as the configured administrator"
ADMIN_TOKEN="$(login "$ADMIN_USERNAME" "$ADMIN_PASSWORD")"
test -n "$ADMIN_TOKEN"

echo "[2/6] Register and login as a normal user"
curl -fsS -X POST "${BASE}/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" >/dev/null
USER_TOKEN="$(login "$USER" "$PASS")"
test -n "$USER_TOKEN"

PROBLEM_BODY="$(jq -cn --arg id "$PROBLEM_ID" '{
  id:$id,title:"Smoke Sum",description:"Return the sum of a and b.",difficulty:"EASY",
  tags:["math"],
  codeStubs:{javascript:"function sum(a, b) {\n  return 0;\n}"},
  testCases:[
    {input:{a:2,b:3},output:5,hidden:false},
    {input:{a:-4,b:9},output:5,hidden:true}
  ]
}')"

echo "[3/6] Confirm normal users cannot create problems"
CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE}/leetcode/admin/problems" \
  -H "Authorization: Bearer ${USER_TOKEN}" -H 'Content-Type: application/json' -d "$PROBLEM_BODY")"
test "$CODE" = "403"

echo "[4/6] Create a problem with public and hidden test cases"
curl -fsS -X POST "${BASE}/leetcode/admin/problems" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" -H 'Content-Type: application/json' \
  -d "$PROBLEM_BODY" | grep -F '"hidden":true' >/dev/null

echo "[5/6] Read the problem through the solver API"
curl -fsS "${BASE}/leetcode/problems/${PROBLEM_ID}" \
  -H "Authorization: Bearer ${USER_TOKEN}" | grep -F 'Smoke Sum' >/dev/null

echo "[6/6] Submit JavaScript and wait for the judge"
SUBMISSION="$(curl -fsS -X POST "${BASE}/leetcode/problems/${PROBLEM_ID}/submit" \
  -H "Authorization: Bearer ${USER_TOKEN}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: smoke-${STAMP}" \
  -d '{"language":"javascript","code":"function sum(a, b) { return a + b; }"}')"
SUBMISSION_ID="$(printf '%s' "$SUBMISSION" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
test -n "$SUBMISSION_ID"

for _ in 1 2 3 4 5 6 7 8 9 10; do
  RESULT="$(curl -fsS "${BASE}/leetcode/submissions/${SUBMISSION_ID}" -H "Authorization: Bearer ${USER_TOKEN}")"
  if printf '%s' "$RESULT" | grep -F '"status":"ACCEPTED"' >/dev/null; then
    echo "LeetCode smoke test passed."
    exit 0
  fi
  if ! printf '%s' "$RESULT" | grep -E '"status":"(QUEUED|RUNNING)"' >/dev/null; then
    echo "$RESULT"
    exit 1
  fi
  sleep 1
done

echo "Judge did not finish in time"
exit 1

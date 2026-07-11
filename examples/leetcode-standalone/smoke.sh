#!/usr/bin/env bash
set -euo pipefail

HOST="${HOST:-http://localhost:18000}"
KONG_ADMIN_URL="${KONG_ADMIN_URL:-http://localhost:18001}"
USER="leetcode_alice_$(date +%s)"
PASS="password123"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "Waiting for Kong to be ready..."
(cd "$ROOT_DIR" && ./kong/setup-core.sh)

# Register the LeetCode route after the service container is up.
echo "Running LeetCode Kong setup script..."
(cd "$ROOT_DIR" && ./leetcode-service/plug/kong-setup.sh "$KONG_ADMIN_URL")

echo "1. Register Alice"
curl -fsS -X POST "$HOST/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" >/dev/null

echo "2. Login Alice"
TOKEN=$(curl -fsS -X POST "$HOST/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

if [[ -z "$TOKEN" ]]; then
  echo "Failed to obtain auth token" >&2
  exit 1
fi

echo "3. Fetch Problems List"
curl -fsS -X GET "$HOST/leetcode/problems" \
  -H "Authorization: Bearer $TOKEN"

echo
echo "4. Create Competition"
COMP_ID="comp-$(date +%s)"
START_TIME="$(date -u -d '1 minute ago' +%Y-%m-%dT%H:%M:%SZ)"
END_TIME="$(date -u -d '30 minutes' +%Y-%m-%dT%H:%M:%SZ)"
curl -fsS -X POST "$HOST/leetcode/competitions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"$COMP_ID\",\"title\":\"Weekly Contest 1\",\"startTime\":\"$START_TIME\",\"endTime\":\"$END_TIME\",\"problemIds\":[\"two-sum\",\"reverse-string\"]}" >/dev/null

poll_submission() {
  local submission_id="$1"
  local expected="$2"
  local result status
  for _ in $(seq 1 40); do
    result="$(curl -fsS "$HOST/leetcode/submissions/$submission_id" -H "Authorization: Bearer $TOKEN")"
    status="$(printf '%s' "$result" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"
    case "$status" in
      QUEUED|RUNNING) sleep 0.5 ;;
      "$expected") return 0 ;;
      *) echo "Expected $expected, got $status: $result" >&2; return 1 ;;
    esac
  done
  echo "Submission $submission_id did not finish before timeout" >&2
  return 1
}

echo
echo "5. Submit Python Solution to Two-Sum"
TWO_SUM="$(curl -fsS -X POST "$HOST/leetcode/problems/two-sum/submit?competitionId=$COMP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"language": "python", "code": "class Solution:\n    def twoSum(self, nums, target):\n        for i in range(len(nums)):\n            for j in range(i+1, len(nums)):\n                if nums[i] + nums[j] == target:\n                    return [i, j]"}')"
TWO_SUM_ID="$(printf '%s' "$TWO_SUM" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
test -n "$TWO_SUM_ID" || { echo "Missing submission id: $TWO_SUM" >&2; exit 1; }
poll_submission "$TWO_SUM_ID" ACCEPTED

echo
echo "6. Submit Javascript Solution to Reverse-String"
REVERSE="$(curl -fsS -X POST "$HOST/leetcode/problems/reverse-string/submit?competitionId=$COMP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"language": "javascript", "code": "var reverseString = function(s) { return s.reverse(); };"}')"
REVERSE_ID="$(printf '%s' "$REVERSE" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
test -n "$REVERSE_ID" || { echo "Missing submission id: $REVERSE" >&2; exit 1; }
poll_submission "$REVERSE_ID" ACCEPTED

echo
echo "7. Fetch Leaderboard"
LEADERBOARD="$(curl -fsS -X GET "$HOST/leetcode/competitions/$COMP_ID/leaderboard" \
  -H "Authorization: Bearer $TOKEN")"
printf '%s' "$LEADERBOARD" | grep -F "\"username\":\"$USER\"" >/dev/null || {
  echo "Leaderboard does not contain $USER: $LEADERBOARD" >&2
  exit 1
}
printf '%s' "$LEADERBOARD" | grep -F '"solvedCount":2' >/dev/null || {
  echo "Leaderboard does not show two solved problems: $LEADERBOARD" >&2
  exit 1
}

echo
echo "Smoke test completed successfully."

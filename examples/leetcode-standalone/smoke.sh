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
curl -fsS -X POST "$HOST/leetcode/competitions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"id\": \"$COMP_ID\", \"title\": \"Weekly Contest 1\"}"

echo
echo "5. Submit Python Solution to Two-Sum"
curl -fsS -X POST "$HOST/leetcode/problems/two-sum/submit?competitionId=$COMP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"language": "python", "code": "class Solution:\n    def twoSum(self, nums, target):\n        for i in range(len(nums)):\n            for j in range(i+1, len(nums)):\n                if nums[i] + nums[j] == target:\n                    return [i, j]"}'

echo
echo "6. Submit Javascript Solution to Reverse-String"
curl -fsS -X POST "$HOST/leetcode/problems/reverse-string/submit?competitionId=$COMP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"language": "javascript", "code": "var reverseString = function(s) { return s.reverse(); };"}'

echo
echo "7. Fetch Leaderboard"
curl -fsS -X GET "$HOST/leetcode/competitions/$COMP_ID/leaderboard" \
  -H "Authorization: Bearer $TOKEN"

echo
echo "Smoke test completed successfully."

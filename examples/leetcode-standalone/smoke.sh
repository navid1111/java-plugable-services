#!/usr/bin/env bash
set -e

HOST="http://localhost:18000"

echo "Waiting for Kong to be ready..."
sleep 15

# 1. Register Auth plugin & LeetCode plugin via admin API
echo "Running Kong setup scripts..."
../../auth-service/plug/kong-setup.sh http://localhost:8001
../../leetcode-service/plug/kong-setup.sh http://localhost:8001

echo "1. Register Alice"
curl -s -X POST $HOST/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice", "password":"password123"}'

echo "2. Login Alice"
TOKEN=$(curl -s -X POST $HOST/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice", "password":"password123"}' | grep -o '"token":"[^"]*' | grep -o '[^"]*$')

echo "Alice Token: $TOKEN"

echo "3. Fetch Problems List"
curl -s -X GET $HOST/leetcode/problems -H "Authorization: Bearer $TOKEN"

echo "4. Create Competition (comp-1)"
curl -s -X POST $HOST/leetcode/competitions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id": "comp-1", "title": "Weekly Contest 1"}'

echo "5. Submit Python Solution to Two-Sum"
curl -s -X POST "$HOST/leetcode/problems/two-sum/submit?competitionId=comp-1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"language": "python", "code": "class Solution:\\n    def twoSum(self, nums, target):\\n        for i in range(len(nums)):\\n            for j in range(i+1, len(nums)):\\n                if nums[i] + nums[j] == target:\\n                    return [i, j]"}'

echo "6. Submit Javascript Solution to Reverse-String"
curl -s -X POST "$HOST/leetcode/problems/reverse-string/submit?competitionId=comp-1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"language": "javascript", "code": "var reverseString = function(s) { return s.reverse(); };"}'

echo "7. Fetch Leaderboard for comp-1"
curl -s -X GET $HOST/leetcode/competitions/comp-1/leaderboard -H "Authorization: Bearer $TOKEN"

echo -e "\\nSmoke test completed successfully."

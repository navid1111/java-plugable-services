#!/usr/bin/env bash
set -e

KONG_ADMIN_URL=${1:-http://localhost:8001}

echo "Configuring Kong for leetcode-service at $KONG_ADMIN_URL..."

# 1. Add Service
curl -s -X POST $KONG_ADMIN_URL/services \
  -d "name=leetcode-service" \
  -d "url=http://leetcode-service:8080/leetcode" \
  || curl -s -X PATCH $KONG_ADMIN_URL/services/leetcode-service \
  -d "url=http://leetcode-service:8080/leetcode"

# 2. Add Route
curl -s -X POST $KONG_ADMIN_URL/services/leetcode-service/routes \
  -d "name=leetcode-route" \
  -d "paths[]=/leetcode" \
  -d "strip_path=false" \
  || curl -s -X PATCH $KONG_ADMIN_URL/services/leetcode-service/routes/leetcode-route \
  -d "paths[]=/leetcode" \
  -d "strip_path=false"

# 3. Add Rate Limiting Plugin
curl -s -X POST $KONG_ADMIN_URL/services/leetcode-service/plugins \
  -d "name=rate-limiting" \
  -d "config.minute=60" \
  -d "config.policy=local" \
  || true

# 4. Add JWT Plugin (Since some endpoints could be public, in a robust setup we might apply 
# this selectively per path, but for simplicity of this demo, we can just apply globally,
# or let the service handle 401s if JWT is missing. For now, Kong will apply JWT globally)
curl -s -X POST $KONG_ADMIN_URL/services/leetcode-service/plugins \
  -d "name=jwt" \
  || true

echo "LeetCode service configured in Kong."

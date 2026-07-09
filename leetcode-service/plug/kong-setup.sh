#!/usr/bin/env bash
set -euo pipefail

KONG_ADMIN_URL=${1:-${KONG_ADMIN_URL:-http://localhost:18001}}
UPSTREAM_HOST="${LEETCODE_UPSTREAM_HOST:-leetcode-service}"
UPSTREAM_PORT="${LEETCODE_UPSTREAM_PORT:-8080}"

echo "==> [leetcode-service] Service 'leetcode-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${KONG_ADMIN_URL}/services/leetcode-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [leetcode-service] PROTECTED route 'leetcode-route' on /leetcode"
curl -fsS -X PUT "${KONG_ADMIN_URL}/services/leetcode-service/routes/leetcode-route" \
  --data "paths[]=/leetcode" \
  --data "strip_path=false" >/dev/null

echo "==> [leetcode-service] Enabling jwt on the service"
curl -fsS -X POST "${KONG_ADMIN_URL}/services/leetcode-service/plugins" \
  --data "name=jwt" \
  --data "config.claims_to_verify=exp" >/dev/null 2>&1 \
  || echo "    jwt already enabled, skipping."

echo "==> [leetcode-service] Enabling rate-limiting on the service (60/min)"
curl -fsS -X POST "${KONG_ADMIN_URL}/services/leetcode-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=60" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

echo "LeetCode setup complete."

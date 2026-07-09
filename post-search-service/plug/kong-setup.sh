#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:18001}"
UPSTREAM_HOST="${POST_SEARCH_UPSTREAM_HOST:-post-search-service}"
UPSTREAM_PORT="${POST_SEARCH_UPSTREAM_PORT:-8080}"

echo "==> [post-search-service] Service 'post-search-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/post-search-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [post-search-service] PROTECTED route 'post-search-route' on /post-search"
curl -fsS -X PUT "${ADMIN}/services/post-search-service/routes/post-search-route" \
  --data "paths[]=/post-search" \
  --data "strip_path=false" >/dev/null

echo "==> [post-search-service] Enabling jwt on the service"
curl -fsS -X POST "${ADMIN}/services/post-search-service/plugins" \
  --data "name=jwt" \
  --data "config.claims_to_verify=exp" >/dev/null 2>&1 \
  || echo "    jwt already enabled, skipping."

echo "==> [post-search-service] Enabling rate-limiting on the service (10/min, 100/hour)"
curl -fsS -X POST "${ADMIN}/services/post-search-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=10" \
  --data "config.hour=100" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

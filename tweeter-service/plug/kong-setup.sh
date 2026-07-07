#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:8001}"
UPSTREAM_HOST="${TWEETER_UPSTREAM_HOST:-tweeter-service}"
UPSTREAM_PORT="${TWEETER_UPSTREAM_PORT:-8080}"

echo "==> [tweeter-service] Service 'tweeter-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/tweeter-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [tweeter-service] PROTECTED route 'tweeter-route' on /posts"
curl -fsS -X PUT "${ADMIN}/services/tweeter-service/routes/tweeter-route" \
  --data "paths[]=/posts" \
  --data "strip_path=false" >/dev/null

echo "==> [tweeter-service] Enabling jwt on the service"
curl -fsS -X POST "${ADMIN}/services/tweeter-service/plugins" \
  --data "name=jwt" \
  --data "config.claims_to_verify=exp" >/dev/null 2>&1 \
  || echo "    jwt already enabled, skipping."

echo "==> [tweeter-service] Enabling rate-limiting on the service (10/min, 100/hour)"
curl -fsS -X POST "${ADMIN}/services/tweeter-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=10" \
  --data "config.hour=100" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

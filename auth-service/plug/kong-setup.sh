#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:18001}"
UPSTREAM_HOST="${AUTH_UPSTREAM_HOST:-auth-service}"
UPSTREAM_PORT="${AUTH_UPSTREAM_PORT:-8080}"

echo "==> [auth-service] Service 'auth-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/auth-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [auth-service] PUBLIC route 'auth-route' on /auth (no token needed)"
curl -fsS -X PUT "${ADMIN}/services/auth-service/routes/auth-route" \
  --data "paths[]=/auth" \
  --data "strip_path=false" >/dev/null

echo "==> [auth-service] Enabling rate-limiting on the service (10/min, 100/hour)"
curl -fsS -X POST "${ADMIN}/services/auth-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=10" \
  --data "config.hour=100" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

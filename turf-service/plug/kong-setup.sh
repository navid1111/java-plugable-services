#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:18001}"
UPSTREAM_HOST="${TURF_UPSTREAM_HOST:-turf-service}"
UPSTREAM_PORT="${TURF_UPSTREAM_PORT:-8080}"

echo "==> [turf-service] Service 'turf-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/turf-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [turf-service] PROTECTED route 'turf-route' on /bookings"
curl -fsS -X PUT "${ADMIN}/services/turf-service/routes/turf-route" \
  --data "paths[]=/bookings" \
  --data "strip_path=false" >/dev/null

echo "==> [turf-service] Enabling jwt on the service"
curl -fsS -X POST "${ADMIN}/services/turf-service/plugins" \
  --data "name=jwt" \
  --data "config.claims_to_verify=exp" >/dev/null 2>&1 \
  || echo "    jwt already enabled, skipping."

echo "==> [turf-service] Enabling rate-limiting on the service (30/min, 300/hour)"
curl -fsS -X POST "${ADMIN}/services/turf-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=30" \
  --data "config.hour=300" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

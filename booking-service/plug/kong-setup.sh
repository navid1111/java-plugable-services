#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:18001}"
UPSTREAM_HOST="${BOOKING_UPSTREAM_HOST:-booking-service}"
UPSTREAM_PORT="${BOOKING_UPSTREAM_PORT:-8080}"

echo "==> [booking-service] Service 'booking-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/booking-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [booking-service] PROTECTED route 'booking-route' on /bookings"
curl -fsS -X PUT "${ADMIN}/services/booking-service/routes/booking-route" \
  --data "paths[]=/bookings" \
  --data "strip_path=false" >/dev/null

echo "==> [booking-service] Enabling jwt on the service"
curl -fsS -X POST "${ADMIN}/services/booking-service/plugins" \
  --data "name=jwt" \
  --data "config.claims_to_verify=exp" >/dev/null 2>&1 \
  || echo "    jwt already enabled, skipping."

echo "==> [booking-service] Enabling rate-limiting on the service (30/min, 300/hour)"
curl -fsS -X POST "${ADMIN}/services/booking-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=30" \
  --data "config.hour=300" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

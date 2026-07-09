#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:8001}"
UPSTREAM_HOST="${MEDIA_UPSTREAM_HOST:-media-service}"
UPSTREAM_PORT="${MEDIA_UPSTREAM_PORT:-8080}"

echo "==> [media-service] Service 'media-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/media-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [media-service] PROTECTED route 'media-route' on /media"
curl -fsS -X PUT "${ADMIN}/services/media-service/routes/media-route" \
  --data "paths[]=/media" \
  --data "strip_path=false" >/dev/null

echo "==> [media-service] Enabling jwt on the service"
curl -fsS -X POST "${ADMIN}/services/media-service/plugins" \
  --data "name=jwt" \
  --data "config.claims_to_verify=exp" >/dev/null 2>&1 \
  || echo "    jwt already enabled, skipping."

echo "==> [media-service] Enabling rate-limiting on the service (10/min, 100/hour)"
curl -fsS -X POST "${ADMIN}/services/media-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=10" \
  --data "config.hour=100" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

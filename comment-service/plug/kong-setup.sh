#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:18001}"
UPSTREAM_HOST="${COMMENT_UPSTREAM_HOST:-comment-service}"
UPSTREAM_PORT="${COMMENT_UPSTREAM_PORT:-8080}"

echo "==> [comment-service] Service 'comment-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/comment-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [comment-service] PROTECTED route 'comment-route' on /comments"
curl -fsS -X PUT "${ADMIN}/services/comment-service/routes/comment-route" \
  --data "paths[]=/comments" \
  --data "strip_path=false" >/dev/null

echo "==> [comment-service] Enabling jwt on the service"
curl -fsS -X POST "${ADMIN}/services/comment-service/plugins" \
  --data "name=jwt" \
  --data "config.claims_to_verify=exp" >/dev/null 2>&1 \
  || echo "    jwt already enabled, skipping."

echo "==> [comment-service] Enabling rate-limiting on the service (10/min, 100/hour)"
curl -fsS -X POST "${ADMIN}/services/comment-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=10" \
  --data "config.hour=100" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

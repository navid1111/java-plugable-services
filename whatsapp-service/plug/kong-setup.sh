#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:8001}"
UPSTREAM_HOST="${CHAT_UPSTREAM_HOST:-whatsapp-service}"
UPSTREAM_PORT="${CHAT_UPSTREAM_PORT:-8080}"

echo "==> [whatsapp-service] Service 'whatsapp-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/whatsapp-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [whatsapp-service] PROTECTED route 'chat-route' on /chat"
curl -fsS -X PUT "${ADMIN}/services/whatsapp-service/routes/chat-route" \
  --data "paths[]=/chat" \
  --data "strip_path=false" >/dev/null

echo "==> [whatsapp-service] Enabling jwt on the service"
curl -fsS -X POST "${ADMIN}/services/whatsapp-service/plugins" \
  --data "name=jwt" \
  --data "config.claims_to_verify=exp" >/dev/null 2>&1 \
  || echo "    jwt already enabled, skipping."

echo "==> [whatsapp-service] Enabling rate-limiting on the service (10/min, 100/hour)"
curl -fsS -X POST "${ADMIN}/services/whatsapp-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=10" \
  --data "config.hour=100" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

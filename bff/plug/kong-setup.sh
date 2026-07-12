#!/usr/bin/env bash
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:18001}"
UPSTREAM_HOST="${BFF_UPSTREAM_HOST:-bff}"
UPSTREAM_PORT="${BFF_UPSTREAM_PORT:-8080}"

echo "==> [bff] Service 'bff' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/bff" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [bff] JWT-protected route 'bff-route' on /bff"
curl -fsS -X PUT "${ADMIN}/services/bff/routes/bff-route" \
  --data "paths[]=/bff" \
  --data "strip_path=false" >/dev/null

if ! curl -fsS "${ADMIN}/services/bff/plugins" \
    | grep -q '"name":"jwt"'; then
  curl -fsS -X POST "${ADMIN}/services/bff/plugins" \
    --data "name=jwt" \
    --data "config.claims_to_verify=exp" >/dev/null
fi

echo "BFF route setup complete."

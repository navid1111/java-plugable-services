#!/usr/bin/env bash
#
# Configures the media-service route and plugins in Kong.
#
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:8001}"

echo "==> Waiting for Kong Admin API at ${ADMIN} ..."
until curl -fsS "${ADMIN}" >/dev/null 2>&1; do
  sleep 2
done
echo "    Kong is up."

echo "==> Delegating to media-service plug kit..."
./media-service/plug/kong-setup.sh

echo "Media setup complete."

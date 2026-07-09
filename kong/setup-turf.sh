#!/usr/bin/env bash
#
# Configures the turf-service route and plugins in Kong.
#
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:18001}"

echo "==> Waiting for Kong Admin API at ${ADMIN} ..."
until curl -fsS "${ADMIN}" >/dev/null 2>&1; do
  sleep 2
done
echo "    Kong is up."

echo "==> Delegating to turf-service plug kit..."
./turf-service/plug/kong-setup.sh

echo "Turf setup complete."

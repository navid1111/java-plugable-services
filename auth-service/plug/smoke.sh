#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
USER="smoke_user_$(date +%s)"
PASS="smoke_pass"

echo "Running smoke test against ${BASE}"

echo "[1/3] Registering..."
curl -fsS -X POST "${BASE}/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" >/dev/null

echo "[2/3] Logging in..."
TOKEN=$(curl -fsS -X POST "${BASE}/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

if [ -z "$TOKEN" ]; then
  echo "Failed to get token!"
  exit 1
fi

echo "[3/3] Fetching /auth/me..."
RESP=$(curl -fsS -w "%{http_code}" -X GET "${BASE}/auth/me" -H "Authorization: Bearer ${TOKEN}")
HTTP_CODE=${RESP:${#RESP}-3}

if [ "$HTTP_CODE" != "200" ]; then
  echo "Failed to fetch /auth/me! HTTP $HTTP_CODE"
  exit 1
fi

echo "Smoke test passed successfully."

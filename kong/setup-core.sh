#!/usr/bin/env bash
#
# Configures core Kong features for the whole stack.
#
set -euo pipefail

ENV_JWT_SECRET="${JWT_SECRET:-}"
ENV_JWT_ISSUER="${JWT_ISSUER:-}"

if [ -f .env ]; then
  source .env
fi

ADMIN="${KONG_ADMIN_URL:-http://localhost:18001}"
JWT_SECRET="${ENV_JWT_SECRET:-${JWT_SECRET:-change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789}}"
JWT_ISSUER="${ENV_JWT_ISSUER:-${JWT_ISSUER:-springboot-auth}}"

echo "==> Waiting for Kong Admin API at ${ADMIN} ..."
until curl -fsS "${ADMIN}" >/dev/null 2>&1; do
  sleep 2
done
echo "    Kong is up."

echo "==> [Core] Global CORS plugin for browser-based generated apps"
if curl -fsS "${ADMIN}/plugins/app-builder-cors" >/dev/null 2>&1; then
  curl -fsS -X PATCH "${ADMIN}/plugins/app-builder-cors" \
    --data "name=cors" \
    --data "config.origins=*" \
    --data "config.methods=GET" \
    --data "config.methods=POST" \
    --data "config.methods=PUT" \
    --data "config.methods=PATCH" \
    --data "config.methods=DELETE" \
    --data "config.methods=OPTIONS" \
    --data "config.headers=Authorization" \
    --data "config.headers=Content-Type" \
    --data "config.headers=Accept" \
    --data "config.credentials=false" >/dev/null
else
  curl -fsS -X PUT "${ADMIN}/plugins/app-builder-cors" \
    --data "name=cors" \
    --data "config.origins=*" \
    --data "config.methods=GET" \
    --data "config.methods=POST" \
    --data "config.methods=PUT" \
    --data "config.methods=PATCH" \
    --data "config.methods=DELETE" \
    --data "config.methods=OPTIONS" \
    --data "config.headers=Authorization" \
    --data "config.headers=Content-Type" \
    --data "config.headers=Accept" \
    --data "config.credentials=false" >/dev/null
fi

echo "==> Delegating to auth-service plug kit..."
./auth-service/plug/kong-setup.sh

echo "==> [Core] Issuer consumer 'springboot-auth' + jwt credential (HS256)"
curl -fsS -X PUT "${ADMIN}/consumers/springboot-auth" >/dev/null
curl -fsS -X POST "${ADMIN}/consumers/springboot-auth/jwt" \
  --data "algorithm=HS256" \
  --data "key=${JWT_ISSUER}" \
  --data "secret=${JWT_SECRET}" >/dev/null 2>&1 \
  || {
    echo "    jwt credential already exists, updating secret."
    curl -fsS -X PATCH "${ADMIN}/consumers/springboot-auth/jwt/${JWT_ISSUER}" \
      --data "algorithm=HS256" \
      --data "key=${JWT_ISSUER}" \
      --data "secret=${JWT_SECRET}" >/dev/null
  }

echo "Core setup complete."

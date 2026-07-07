#!/usr/bin/env bash
#
# Configures core Kong features for the whole stack.
#
set -euo pipefail

if [ -f .env ]; then
  source .env
fi

ADMIN="${KONG_ADMIN_URL:-http://localhost:8001}"
JWT_SECRET="${JWT_SECRET:-change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789}"
JWT_ISSUER="${JWT_ISSUER:-springboot-auth}"

echo "==> Waiting for Kong Admin API at ${ADMIN} ..."
until curl -fsS "${ADMIN}" >/dev/null 2>&1; do
  sleep 2
done
echo "    Kong is up."

echo "==> Delegating to auth-service plug kit..."
./auth-service/plug/kong-setup.sh

echo "==> [Core] Issuer consumer 'springboot-auth' + jwt credential (HS256)"
curl -fsS -X PUT "${ADMIN}/consumers/springboot-auth" >/dev/null
curl -fsS -X POST "${ADMIN}/consumers/springboot-auth/jwt" \
  --data "algorithm=HS256" \
  --data "key=${JWT_ISSUER}" \
  --data "secret=${JWT_SECRET}" >/dev/null 2>&1 \
  || echo "    jwt credential already exists, skipping."

echo "Core setup complete."

#!/usr/bin/env bash
#
# Configures Kong (DB-backed mode) for JWT-based end-user auth via its Admin API:
#   1. a Service pointing at the Spring Boot app
#   2. a PUBLIC route for /auth (login/register — no token required)
#   3. a PROTECTED route for /api guarded by the jwt plugin
#   4. rate-limiting on the whole service
#   5. an "issuer" Consumer holding the jwt credential (shared HS256 secret)
#
# The JWT_SECRET and JWT_ISSUER below MUST match the app's env in docker-compose.yml:
# Kong looks a token up by its `iss` claim (= credential key) and verifies the
# signature with the same shared secret.
#
# Run AFTER `docker compose up` is healthy:
#   ./kong/setup.sh
#
set -euo pipefail

ADMIN="${KONG_ADMIN_URL:-http://localhost:8001}"
UPSTREAM_HOST="${UPSTREAM_HOST:-app}"
UPSTREAM_PORT="${UPSTREAM_PORT:-8080}"

# --- These two MUST match docker-compose.yml's app service (JWT_SECRET / JWT_ISSUER) ---
JWT_SECRET="${JWT_SECRET:-change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789}"
JWT_ISSUER="${JWT_ISSUER:-springboot-auth}"

echo "==> Waiting for Kong Admin API at ${ADMIN} ..."
until curl -fsS "${ADMIN}" >/dev/null 2>&1; do
  sleep 2
done
echo "    Kong is up."

echo "==> [1/6] Service 'springboot-service' -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}"
curl -fsS -X PUT "${ADMIN}/services/springboot-service" \
  --data "url=http://${UPSTREAM_HOST}:${UPSTREAM_PORT}" >/dev/null

echo "==> [2/6] PUBLIC route 'auth-route' on /auth (no token needed)"
curl -fsS -X PUT "${ADMIN}/services/springboot-service/routes/auth-route" \
  --data "paths[]=/auth" \
  --data "strip_path=false" >/dev/null

echo "==> [3/6] PROTECTED route 'api-route' on /api"
curl -fsS -X PUT "${ADMIN}/services/springboot-service/routes/api-route" \
  --data "paths[]=/api" \
  --data "strip_path=false" >/dev/null

echo "==> [4/6] Enabling jwt plugin on 'api-route' only"
curl -fsS -X POST "${ADMIN}/routes/api-route/plugins" \
  --data "name=jwt" >/dev/null 2>&1 \
  || echo "    jwt already enabled on api-route, skipping."

echo "==> [5/6] Enabling rate-limiting on the service (10/min, 100/hour)"
curl -fsS -X POST "${ADMIN}/services/springboot-service/plugins" \
  --data "name=rate-limiting" \
  --data "config.minute=10" \
  --data "config.hour=100" \
  --data "config.policy=local" >/dev/null 2>&1 \
  || echo "    rate-limiting already enabled, skipping."

echo "==> [6/6] Issuer consumer 'springboot-auth' + jwt credential (HS256)"
curl -fsS -X PUT "${ADMIN}/consumers/springboot-auth" >/dev/null
curl -fsS -X POST "${ADMIN}/consumers/springboot-auth/jwt" \
  --data "algorithm=HS256" \
  --data "key=${JWT_ISSUER}" \
  --data "secret=${JWT_SECRET}" >/dev/null 2>&1 \
  || echo "    jwt credential already exists, skipping."

echo ""
echo "Done. Try the full flow:"
echo "  # 1. Register a user"
echo "  curl -s -X POST http://localhost:18000/auth/register -H 'Content-Type: application/json' \\"
echo "       -d '{\"username\":\"alice\",\"password\":\"secret123\"}'"
echo "  # 2. Log in -> returns access_token"
echo "  curl -s -X POST http://localhost:18000/auth/login -H 'Content-Type: application/json' \\"
echo "       -d '{\"username\":\"alice\",\"password\":\"secret123\"}'"
echo "  # 3. Call the protected API with the token"
echo "  curl -i http://localhost:18000/api/hello -H 'Authorization: Bearer <token>'"

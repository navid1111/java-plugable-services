#!/usr/bin/env bash
# Happy-path smoke test for the BFF composer through the Kong gateway.
# Verifies that /bff/posts/{id} and /bff/feed return composed, client-shaped reads.
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
USER="bff_user_${STAMP}"
PASS="smoke_pass"

echo "Running bff smoke test against ${BASE}"

# 1. Identity — register (ignore conflict) then login for a user JWT.
curl -fsS -X POST "${BASE}/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" >/dev/null 2>&1 || true

TOKEN="$(curl -fsS -X POST "${BASE}/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
[ -n "${TOKEN}" ] || { echo "FAIL: could not obtain access token"; exit 1; }

# 2. Seed a post on the owning service (writes never go through the BFF).
POST_ID="$(curl -fsS -X POST "${BASE}/posts" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"content\":\"bff smoke post ${STAMP}\"}" \
  | sed -n 's/.*"id":\([0-9]\{1,\}\).*/\1/p')"
[ -n "${POST_ID}" ] || { echo "FAIL: could not create seed post"; exit 1; }
echo "created post ${POST_ID}"

# 3. Composed post detail — critical owner section must be present.
echo "==> GET /bff/posts/${POST_ID}"
curl -fsS "${BASE}/bff/posts/${POST_ID}" -H "Authorization: Bearer ${TOKEN}" \
  | grep -q '"post"' && echo "  composed post detail OK" \
  || { echo "FAIL: /bff/posts/{id} did not return a composed post section"; exit 1; }

# 4. Composed feed — cursor page of composed items.
echo "==> GET /bff/feed"
curl -fsS "${BASE}/bff/feed" -H "Authorization: Bearer ${TOKEN}" \
  | grep -q '"items"' && echo "  composed feed OK" \
  || { echo "FAIL: /bff/feed did not return a composed items page"; exit 1; }

echo "bff smoke test passed"

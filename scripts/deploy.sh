#!/usr/bin/env bash
# Deploy the App Builder publicly via ngrok.
#
# Brings up the full stack the App Builder needs and exposes its UI:
#   1. Docker backend stack  -> Kong gateway on :18000 (+ Spring Boot services)
#   2. Catalog service       -> app-builder Spring Boot on :8080 (/api/plugs)
#   3. App Builder UI        -> FastAPI (uvicorn) on :8090
#   4. ngrok                 -> public URL forwarding to :8090
#
# The catalog on :8080 is REQUIRED: without it, "create app workspace" fails with
# httpx.ConnectError and the UI shows "Could not create an app workspace."
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

: "${NGROK_AUTH_TOKEN:?Set NGROK_AUTH_TOKEN in .env first.}"

CATALOG_PORT="${CATALOG_PORT:-8080}"
APPBUILDER_PORT="${APPBUILDER_PORT:-8090}"
KONG_PORT="${KONG_PORT:-18000}"
# Public URL: use the reserved free domain if given, else let ngrok pick a random one.
NGROK_URL="${NGROK_URL:-${NGROK_DOMAIN:-}}"
LOG_DIR="${LOG_DIR:-$ROOT_DIR/.deploy-logs}"
mkdir -p "$LOG_DIR"

for bin in ngrok docker mvn curl; do
  command -v "$bin" >/dev/null 2>&1 || { echo "$bin is not installed or not on PATH." >&2; exit 1; }
done

CATALOG_PID=""
APPBUILDER_PID=""
cleanup() {
  local code=$?
  trap - EXIT INT TERM
  [[ -n "$APPBUILDER_PID" ]] && kill "$APPBUILDER_PID" >/dev/null 2>&1 || true
  [[ -n "$CATALOG_PID" ]] && kill "$CATALOG_PID" >/dev/null 2>&1 || true
  wait >/dev/null 2>&1 || true
  exit "$code"
}
trap cleanup EXIT INT TERM

wait_for() { # url attempts label
  local url="$1" attempts="$2" label="$3" i
  for ((i = 1; i <= attempts; i++)); do
    if curl -sf -o /dev/null "$url"; then echo "  ${label} is up."; return 0; fi
    sleep 2
  done
  echo "  ${label} did not come up ($url). Check logs in $LOG_DIR." >&2
  return 1
}

echo "1/4 Ensuring Docker backend stack is up (Kong on :${KONG_PORT})..."
docker compose up -d >/dev/null
wait_for "http://127.0.0.1:${KONG_PORT}/" 30 "Kong gateway" || true  # 404 at / is fine; -f only checks reachability

echo "2/4 Starting catalog service (Spring Boot on :${CATALOG_PORT})..."
if curl -sf -o /dev/null "http://127.0.0.1:${CATALOG_PORT}/api/plugs"; then
  echo "  Catalog already running."
else
  ( cd app-builder && exec mvn -q spring-boot:run ) >"$LOG_DIR/catalog.log" 2>&1 &
  CATALOG_PID=$!
  wait_for "http://127.0.0.1:${CATALOG_PORT}/api/plugs" 45 "Catalog /api/plugs"
fi

echo "3/4 Starting App Builder UI (uvicorn on :${APPBUILDER_PORT})..."
if curl -sf -o /dev/null "http://127.0.0.1:${APPBUILDER_PORT}/"; then
  echo "  App Builder already running."
else
  ( cd app-builder/agent-backend && exec .venv/bin/python -m uvicorn app.main:app \
      --host 0.0.0.0 --port "$APPBUILDER_PORT" ) >"$LOG_DIR/appbuilder.log" 2>&1 &
  APPBUILDER_PID=$!
  wait_for "http://127.0.0.1:${APPBUILDER_PORT}/" 30 "App Builder UI"
fi

echo "4/4 Opening ngrok tunnel to :${APPBUILDER_PORT}..."
ngrok config add-authtoken "$NGROK_AUTH_TOKEN" >/dev/null
NGROK_ARGS=(http "$APPBUILDER_PORT")
[[ -n "$NGROK_URL" ]] && NGROK_ARGS=(http "--url=${NGROK_URL#https://}" "$APPBUILDER_PORT")

echo "App Builder will be public once ngrok connects."
[[ -n "$NGROK_URL" ]] && echo "Public URL: https://${NGROK_URL#https://}"
exec ngrok "${NGROK_ARGS[@]}"

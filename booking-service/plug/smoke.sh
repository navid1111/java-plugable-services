#!/usr/bin/env bash
set -euo pipefail

BASE="${KONG_URL:-http://localhost:18000}"
STAMP="$(date +%s)"
ALICE="booking_alice_${STAMP}"
BOB="booking_bob_${STAMP}"
PASS="smoke_pass"
WORK_DIR="${TMPDIR:-/tmp}/booking-smoke-${STAMP}"

echo "Running booking smoke test against ${BASE}"

mkdir -p "$WORK_DIR"

register() {
  local username="$1"
  curl -fsS -X POST "${BASE}/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${username}\",\"password\":\"${PASS}\"}" >/dev/null
}

login() {
  local username="$1"
  curl -fsS -X POST "${BASE}/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${username}\",\"password\":\"${PASS}\"}" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

first_available_slot() {
  node -e '
let data = "";
process.stdin.on("data", chunk => data += chunk);
process.stdin.on("end", () => {
  const resources = JSON.parse(data);
  for (const resource of resources) {
    for (const slot of resource.slots || []) {
      if (slot.available) {
        console.log(slot.id);
        return;
      }
    }
  }
  process.exit(1);
});
'
}

slot_available_state() {
  local slot_id="$1"
  node -e '
const slotId = Number(process.argv[1]);
let data = "";
process.stdin.on("data", chunk => data += chunk);
process.stdin.on("end", () => {
  const resources = JSON.parse(data);
  for (const resource of resources) {
    for (const slot of resource.slots || []) {
      if (slot.id === slotId) {
        console.log(String(slot.available));
        return;
      }
    }
  }
  process.exit(1);
});
' "$slot_id"
}

json_id_from_file() {
  local file="$1"
  sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' "$file"
}

require_contains() {
  local haystack="$1"
  local needle="$2"
  local label="$3"
  if ! printf '%s' "$haystack" | grep -F "$needle" >/dev/null; then
    echo "Expected ${label} to contain '${needle}'"
    echo "$haystack"
    exit 1
  fi
}

echo "[1/9] Registering users..."
register "$ALICE"
register "$BOB"

echo "[2/9] Logging in..."
ALICE_TOKEN="$(login "$ALICE")"
BOB_TOKEN="$(login "$BOB")"

if [ -z "$ALICE_TOKEN" ] || [ -z "$BOB_TOKEN" ]; then
  echo "Failed to get tokens"
  exit 1
fi

echo "[3/9] Verifying Kong rejects missing token..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/bookings/resources")"
if [ "$HTTP_CODE" != "401" ]; then
  echo "Expected 401 for unauthenticated /bookings/resources, got ${HTTP_CODE}"
  exit 1
fi

echo "[4/9] Browsing seeded resources and slots..."
RESOURCES="$(curl -fsS "${BASE}/bookings/resources" -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$RESOURCES" "Meeting Room A" "resources listing"
SLOT_ID="$(printf '%s' "$RESOURCES" | first_available_slot)"
if [ -z "$SLOT_ID" ]; then
  echo "Failed to find an available slot"
  echo "$RESOURCES"
  exit 1
fi

echo "[5/9] Proving concurrent no-double-booking..."
for i in 1 2 3; do
  (
    BODY_FILE="${WORK_DIR}/${i}.body"
    CODE="$(curl -s -o "$BODY_FILE" -w "%{http_code}" -X POST "${BASE}/bookings" \
      -H "Authorization: Bearer ${ALICE_TOKEN}" \
      -H 'Content-Type: application/json' \
      -d "{\"slotId\":${SLOT_ID}}")"
    printf '%s' "$CODE" > "${WORK_DIR}/${i}.code"
  ) &
done
wait

SUCCESS_COUNT=0
CONFLICT_COUNT=0
BOOKING_ID=""
for i in 1 2 3; do
  CODE="$(cat "${WORK_DIR}/${i}.code")"
  if [ "$CODE" = "201" ]; then
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    BOOKING_ID="$(json_id_from_file "${WORK_DIR}/${i}.body")"
  elif [ "$CODE" = "409" ]; then
    CONFLICT_COUNT=$((CONFLICT_COUNT + 1))
  else
    echo "Expected concurrent booking response 201 or 409, got ${CODE}"
    cat "${WORK_DIR}/${i}.body"
    exit 1
  fi
done

if [ "$SUCCESS_COUNT" != "1" ] || [ "$CONFLICT_COUNT" != "2" ] || [ -z "$BOOKING_ID" ]; then
  echo "Expected exactly one success and two conflicts; got success=${SUCCESS_COUNT}, conflict=${CONFLICT_COUNT}, booking=${BOOKING_ID}"
  exit 1
fi

echo "[6/9] Availability flips to unavailable..."
RESOURCES_AFTER_BOOKING="$(curl -fsS "${BASE}/bookings/resources" -H "Authorization: Bearer ${ALICE_TOKEN}")"
AVAILABLE="$(printf '%s' "$RESOURCES_AFTER_BOOKING" | slot_available_state "$SLOT_ID")"
if [ "$AVAILABLE" != "false" ]; then
  echo "Expected booked slot ${SLOT_ID} to be unavailable"
  echo "$RESOURCES_AFTER_BOOKING"
  exit 1
fi

echo "[7/9] Listing Alice's bookings and rejecting Bob's cancel..."
ALICE_MINE="$(curl -fsS "${BASE}/bookings/mine" -H "Authorization: Bearer ${ALICE_TOKEN}")"
require_contains "$ALICE_MINE" "\"id\":${BOOKING_ID}" "Alice bookings"

HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "${BASE}/bookings/${BOOKING_ID}" \
  -H "Authorization: Bearer ${BOB_TOKEN}")"
if [ "$HTTP_CODE" != "403" ]; then
  echo "Expected 403 when Bob cancels Alice's booking, got ${HTTP_CODE}"
  exit 1
fi

echo "[8/9] Cancelling idempotently..."
HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "${BASE}/bookings/${BOOKING_ID}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 when Alice cancels her booking, got ${HTTP_CODE}"
  exit 1
fi

HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "${BASE}/bookings/${BOOKING_ID}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")"
if [ "$HTTP_CODE" != "204" ]; then
  echo "Expected 204 when Alice re-cancels her booking, got ${HTTP_CODE}"
  exit 1
fi

echo "[9/9] Rebooking cancelled slot as Bob..."
BOB_BOOKING="$(curl -fsS -X POST "${BASE}/bookings" \
  -H "Authorization: Bearer ${BOB_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"slotId\":${SLOT_ID}}")"
require_contains "$BOB_BOOKING" "\"username\":\"${BOB}\"" "Bob booking"

echo "Booking smoke test passed successfully."

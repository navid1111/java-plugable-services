# Testing Guide and Terminal Commands

This document outlines the terminal commands required to run, configure, and
test the `turf-service` behind Kong. The turf service provides venue browsing,
slot booking, conflict prevention, cancellation, and rebooking.

The most important behavior to test is the no-double-booking guarantee:
multiple concurrent booking attempts for the same slot must produce exactly one
successful booking and conflicts for the rest.

## 1. Environment Setup

The turf service is protected by Kong's JWT plugin, so the root `.env` must use
the same JWT settings as `auth-service` and Kong:

```bash
echo "JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
```

The issuer must match the Kong JWT credential key. The secret must match the
secret used by `auth-service` when issuing tokens and by Kong when verifying
them.

## 2. Managing The Stack

The `turf-service` is scoped under the `turf` profile. That profile also starts
`auth-service`, because every `/bookings` endpoint requires a valid user token.

**Start the stack (Core + Auth + Turf):**

```bash
docker compose --profile turf up --build -d
```

**Check container health:**

```bash
docker compose --profile turf ps
```

Expected healthy services:

- `kong`
- `kong-database`
- `users-db`
- `auth-service`
- `bookings-db`
- `turf-service`

**Stop the stack without deleting volumes:**

```bash
docker compose --profile turf down
```

**Stop and clean up the stack including volumes:**

```bash
docker compose --profile turf down -v
```

Use `down -v` only when you intentionally want to delete Postgres data.

## 3. Configuring The Gateway

Configure core auth first, then register the protected `/bookings` route:

```bash
./kong/setup-core.sh
./kong/setup-turf.sh
```

`setup-core.sh` registers:

- `/auth` route
- Kong consumer `springboot-auth`
- HS256 JWT credential matching `JWT_SECRET` and `JWT_ISSUER`

`setup-turf.sh` delegates to `turf-service/plug/kong-setup.sh`, which
registers:

- Kong service `turf-service`
- protected `/bookings` route
- `jwt` plugin for all booking endpoints
- rate limiting: 30 requests/minute, 300 requests/hour

## 4. Manual API Testing Using Curl

The API is exposed through Kong at:

```text
http://localhost:18000
```

All `/bookings` endpoints require a valid JWT verified by Kong.

### A. Register Two Users

```bash
curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'

curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"secret123"}'
```

Expected output for each new user: `201 Created`.

### B. Log In And Extract Tokens

```bash
ALICE_TOKEN=$(curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

BOB_TOKEN=$(curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
```

Confirm tokens were extracted:

```bash
printf 'alice token length: %s\n' "${#ALICE_TOKEN}"
printf 'bob token length: %s\n' "${#BOB_TOKEN}"
```

### C. Confirm Kong Rejects Missing Tokens

```bash
curl -i -s http://localhost:18000/bookings/venues
```

Expected output: `401 Unauthorized`.

This rejection happens at Kong before the request reaches `turf-service`.

### D. Browse Venues And Slots

```bash
curl -i -s http://localhost:18000/bookings/venues \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK` with seeded venues and slots. Example fields:

```json
[
  {
    "id": 1,
    "name": "Northside Arena",
    "location": "Mirpur DOHS",
    "slots": [
      {
        "id": 1,
        "startTime": "2026-...",
        "endTime": "2026-...",
        "available": true
      }
    ]
  }
]
```

Extract the first available slot ID without requiring `jq`:

```bash
SLOT_ID=$(curl -fsS http://localhost:18000/bookings/venues \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  | node -e '
let data = "";
process.stdin.on("data", chunk => data += chunk);
process.stdin.on("end", () => {
  const venues = JSON.parse(data);
  for (const venue of venues) {
    for (const slot of venue.slots || []) {
      if (slot.available) {
        console.log(slot.id);
        return;
      }
    }
  }
  process.exit(1);
});
')

echo "$SLOT_ID"
```

### E. Book A Slot

```bash
BOOKING_BODY=$(curl -fsS -X POST http://localhost:18000/bookings \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"slotId\":${SLOT_ID}}")

echo "$BOOKING_BODY"
```

Expected output: `201 Created` with a booking body:

```json
{
  "id": 1,
  "slotId": 1,
  "venueId": 1,
  "venueName": "Northside Arena",
  "venueLocation": "Mirpur DOHS",
  "username": "alice",
  "status": "active",
  "startTime": "2026-...",
  "endTime": "2026-...",
  "createdAt": "2026-...",
  "cancelledAt": null
}
```

Extract the booking ID:

```bash
BOOKING_ID=$(printf '%s' "$BOOKING_BODY" \
  | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')

echo "$BOOKING_ID"
```

### F. Confirm Double-Booking Returns Conflict

```bash
curl -i -s -X POST http://localhost:18000/bookings \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"slotId\":${SLOT_ID}}"
```

Expected output: `409 Conflict` with:

```json
{"error":"slot already booked"}
```

### G. Confirm Availability Flips To False

```bash
curl -s http://localhost:18000/bookings/venues \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  | node -e '
const slotId = Number(process.argv[1]);
let data = "";
process.stdin.on("data", chunk => data += chunk);
process.stdin.on("end", () => {
  const venues = JSON.parse(data);
  for (const venue of venues) {
    for (const slot of venue.slots || []) {
      if (slot.id === slotId) {
        console.log(slot.available);
        return;
      }
    }
  }
  process.exit(1);
});
' "$SLOT_ID"
```

Expected output: `false`.

### H. List My Bookings

```bash
curl -i -s http://localhost:18000/bookings/mine \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK` with Alice's bookings only. The username comes from
the JWT `sub` claim, not from a query parameter.

### I. Reject Cancelling Another User's Booking

```bash
curl -i -s -X DELETE "http://localhost:18000/bookings/${BOOKING_ID}" \
  -H "Authorization: Bearer $BOB_TOKEN"
```

Expected output: `403 Forbidden`.

### J. Cancel A Booking

```bash
curl -i -s -X DELETE "http://localhost:18000/bookings/${BOOKING_ID}" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `204 No Content`.

The cancel operation is idempotent. Running the same command again should also
return `204 No Content`.

### K. Rebook The Freed Slot

```bash
curl -i -s -X POST http://localhost:18000/bookings \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"slotId\":${SLOT_ID}}"
```

Expected output: `201 Created` with `"username":"bob"`.

## 5. Concurrent No-Double-Booking Test

The important production-like test is not just "Alice books, then Bob fails."
It is "three requests race for the same available slot, and exactly one wins."

The built-in smoke script does this automatically:

```bash
./turf-service/plug/smoke.sh
```

Internally it starts three background `curl` processes against the same
`slotId`, waits for all of them, then asserts:

- exactly one response is `201 Created`
- exactly two responses are `409 Conflict`
- the successful booking has an ID

This proves the database constraint is doing real concurrency work.

## 6. Automated Smoke Testing

Run the full smoke test:

```bash
./turf-service/plug/smoke.sh
```

The smoke test covers:

- register Alice and Bob
- login and extract JWTs
- unauthenticated `/bookings/venues` returns `401`
- browse seeded venues and slots
- concurrent no-double-booking proof
- booked slot becomes unavailable
- Alice sees her own booking in `/bookings/mine`
- Bob cannot cancel Alice's booking
- Alice can cancel idempotently
- Bob can rebook the freed slot

Expected final line:

```text
Turf smoke test passed successfully.
```

## 7. Standalone Integration Demo

The standalone demo proves `turf-service` can be plugged into a separate host
project using only images, compose includes, and plug-kit scripts.

From the repo root:

```bash
docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
docker build -t turf-service:latest -f turf-service/Dockerfile turf-service/
```

Then:

```bash
cd examples/turf-standalone
echo "JWT_SECRET=super-secret-jwt-key-for-turf-demo-32-bytes" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
docker compose up -d
../../auth-service/plug/kong-setup.sh
curl -fsS -X PUT http://localhost:8001/consumers/springboot-auth
curl -fsS -X POST http://localhost:8001/consumers/springboot-auth/jwt \
  --data "algorithm=HS256" \
  --data "key=springboot-auth" \
  --data "secret=super-secret-jwt-key-for-turf-demo-32-bytes" \
  || echo "jwt credential already exists, skipping."
../../turf-service/plug/kong-setup.sh
../../turf-service/plug/smoke.sh
docker compose down -v
```

## 8. Troubleshooting

### `401 Unauthorized` on `/bookings`

Likely causes:

- missing `Authorization: Bearer <token>` header
- Kong JWT credential not configured
- `JWT_SECRET` differs between auth and Kong
- token expired

Run:

```bash
./kong/setup-core.sh
./kong/setup-turf.sh
```

Then log in again and retry.

### `404 Not Found` on `/bookings`

The turf route is probably not registered in Kong. Run:

```bash
./kong/setup-turf.sh
```

### `409 Conflict` on `POST /bookings`

This usually means the slot already has an active booking. That is expected
behavior. Cancel the existing booking or choose another available slot.

### No venues returned

Check that `turf-service` is healthy and connected to `bookings-db`:

```bash
docker compose --profile turf ps
docker compose --profile turf logs turf-service
```

The seed data is inserted by `TurfDataInitializer` only when the venues table is
empty.

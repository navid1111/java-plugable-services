# Testing Guide and Terminal Commands

This document outlines the terminal commands required to run, configure, and
test the `comment-service` behind Kong.

## 1. Environment Setup

The comment service depends on JWTs issued by `auth-service` and verified by
Kong. Create a root `.env` file with matching JWT settings:

```bash
echo "JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
```

The secret must be at least 32 bytes for HS256. The issuer must match the Kong
JWT credential key, because Kong uses the token's `iss` claim to find the
verification secret.

## 2. Managing the Stack

The `comment-service` is scoped under the `comments` profile. That profile
also starts `auth-service`, because all `/comments` endpoints require JWTs.

**Start the stack:**

```bash
docker compose --profile comments up --build -d
```

**Check container health:**

```bash
docker compose --profile comments ps
```

Expected healthy services:

- `kong`
- `kong-database`
- `users-db`
- `auth-service`
- `comments-db`
- `comment-service`

**Stop the stack without deleting volumes:**

```bash
docker compose --profile comments down
```

**Stop and clean up the stack including volumes:**

```bash
docker compose --profile comments down -v
```

## 3. Configuring the Gateway

Configure core auth first, then register the protected `/comments` route:

```bash
./kong/setup-core.sh
./kong/setup-comments.sh
```

`setup-core.sh` registers:

- `/auth` route
- Kong consumer `springboot-auth`
- HS256 JWT credential matching `JWT_SECRET` and `JWT_ISSUER`

`setup-comments.sh` registers:

- Kong service `comment-service`
- protected `/comments` route
- `jwt` plugin
- rate limiting: 10 requests/minute, 100 requests/hour

## 4. Manual API Testing Using Curl

The API is exposed through Kong at `http://localhost:18000`. All `/comments`
endpoints require a valid JWT verified by Kong.

### A. Register Two Users

```bash
curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'

curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"secret123"}'
```

### B. Log In and Extract Tokens

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
curl -i -s http://localhost:18000/comments/targets/tweeter.post/123
```

Expected output: `401 Unauthorized`.

### D. Create Comments on a Generic Target

```bash
curl -i -s -X POST http://localhost:18000/comments/targets/tweeter.post/123 \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"first comment from bob"}'
```

Expected output: `201 Created` with id, targetType, targetId, authorUsername,
content, and createdAt.

Create more comments for paging:

```bash
curl -s -X POST http://localhost:18000/comments/targets/tweeter.post/123 \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"second comment from bob"}'

curl -s -X POST http://localhost:18000/comments/targets/tweeter.post/123 \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"third comment from alice"}'
```

### E. Create an Isolated Comment on Another Target

```bash
curl -i -s -X POST http://localhost:18000/comments/targets/youtube.video/abc123 \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"video comment from alice"}'
```

This comment must not appear when reading `tweeter.post/123`.

### F. Read Comments for a Target with Cursor Paging

```bash
curl -i -s "http://localhost:18000/comments/targets/tweeter.post/123?pageSize=2" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK` with:

- `items`: newest-first comments for only `tweeter.post/123`
- `nextCursor`: present when older comments are available

Fetch the next page:

```bash
curl -i -s "http://localhost:18000/comments/targets/tweeter.post/123?pageSize=2&cursor=${CURSOR}" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

### G. Read a Comment by ID

```bash
curl -i -s http://localhost:18000/comments/1 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK` if comment `1` exists, or `404` if it does not.

### H. Reject Non-Owner Delete

If Bob created comment `1`, Alice should not be able to delete it:

```bash
curl -i -s -X DELETE http://localhost:18000/comments/1 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `403 Forbidden`.

### I. Owner Delete

```bash
curl -i -s -X DELETE http://localhost:18000/comments/1 \
  -H "Authorization: Bearer $BOB_TOKEN"
```

Expected output: `204 No Content` when Bob owns comment `1`.

## 5. Automated Smoke Testing

Use the built-in smoke test script:

```bash
./comment-service/plug/smoke.sh
```

The smoke test registers users, verifies `401` without a token, comments on
two target namespaces, verifies target isolation, walks cursor paging, rejects
non-owner delete, and allows owner delete.

## 6. Standalone Integration Demo

To prove the service can run independently of any target service, use the
standalone demo:

```bash
docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
docker build -t comment-service:latest -f comment-service/Dockerfile comment-service/

cd examples/comments-standalone
echo "JWT_SECRET=super-secret-jwt-key-for-comments-demo-32-bytes" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
docker compose up -d
../../auth-service/plug/kong-setup.sh

JWT_SECRET="$(grep '^JWT_SECRET=' .env | cut -d= -f2-)"
JWT_ISSUER="$(grep '^JWT_ISSUER=' .env | cut -d= -f2-)"
curl -fsS -X PUT http://localhost:8001/consumers/springboot-auth
curl -fsS -X POST http://localhost:8001/consumers/springboot-auth/jwt \
  --data "algorithm=HS256" \
  --data "key=${JWT_ISSUER}" \
  --data "secret=${JWT_SECRET}" \
  || curl -fsS -X PATCH "http://localhost:8001/consumers/springboot-auth/jwt/${JWT_ISSUER}" \
    --data "algorithm=HS256" \
    --data "key=${JWT_ISSUER}" \
    --data "secret=${JWT_SECRET}"

../../comment-service/plug/kong-setup.sh
../../comment-service/plug/smoke.sh
docker compose down -v
```

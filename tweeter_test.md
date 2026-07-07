# Testing Guide and Terminal Commands

This document outlines the terminal commands required to run, configure, and
test the `tweeter-service` behind Kong.

## 1. Environment Setup

The tweeter service depends on auth tokens, so the root `.env` must contain the
same JWT settings used by `auth-service` and Kong:

```bash
echo "JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
```

## 2. Managing the Stack

The `tweeter-service` is scoped under the `tweeter` profile. That profile also
starts `auth-service`, because the smoke flow needs users and JWTs.

**Start the stack (Core + Auth + Tweeter):**

```bash
docker compose --profile tweeter up --build -d
```

**Stop and clean up the stack (including volumes):**

```bash
docker compose --profile tweeter down -v
```

## 3. Configuring the Gateway

Configure core auth first, then register the `/posts` route:

```bash
./kong/setup-core.sh
./kong/setup-tweeter.sh
```

## 4. Manual API Testing Using Curl

The API is exposed on `http://localhost:18000`. All `/posts` endpoints require
a valid JWT verified by Kong.

### A. Register and Log In Two Users

```bash
curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'

curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"secret123"}'
```

Extract their tokens:

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

### B. Confirm Kong Rejects Missing Tokens

```bash
curl -i -s http://localhost:18000/posts/feed
```

Expected output: `401 Unauthorized`.

### C. Create a Post

```bash
curl -i -s -X POST http://localhost:18000/posts \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"hello from bob"}'
```

Expected output: `201 Created` with the post ID, content, `authorUsername`,
and `createdAt`.

### D. Read a Post by ID

```bash
curl -i -s http://localhost:18000/posts/1 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK` with the post JSON, if post `1` exists.

### E. List Posts by Author

```bash
curl -i -s "http://localhost:18000/posts?author=bob" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK` with Bob's posts newest-first.

### F. Follow a User

```bash
curl -i -s -X PUT http://localhost:18000/posts/users/bob/follow \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK`. The operation is idempotent.

### G. Read the Feed with Cursor Paging

```bash
curl -i -s "http://localhost:18000/posts/feed?pageSize=2" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `200 OK` with `items` and a `nextCursor` when more posts are
available.

### H. Unfollow a User

```bash
curl -i -s -X DELETE http://localhost:18000/posts/users/bob/follow \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

Expected output: `204 No Content`. The operation is idempotent.

## 5. Automated Smoke Testing

Use the built-in smoke test script for the full two-user flow:

```bash
./tweeter-service/plug/smoke.sh
```

The smoke test registers two users, verifies `401` without a token, follows,
creates posts, checks author listing, walks feed paging, and confirms unfollow
removes posts from the feed.

## 6. Standalone Integration Demo

To prove the service can run independently, use the standalone demo:

```bash
docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
docker build -t tweeter-service:latest -f tweeter-service/Dockerfile tweeter-service/

cd examples/tweeter-standalone
echo "JWT_SECRET=super-secret-jwt-key-for-tweeter-demo-32-bytes" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
docker compose up -d
../../auth-service/plug/kong-setup.sh
curl -fsS -X PUT http://localhost:8001/consumers/springboot-auth
curl -fsS -X POST http://localhost:8001/consumers/springboot-auth/jwt \
  --data "algorithm=HS256" \
  --data "key=springboot-auth" \
  --data "secret=super-secret-jwt-key-for-tweeter-demo-32-bytes" \
  || echo "jwt credential already exists, skipping."
../../tweeter-service/plug/kong-setup.sh
../../tweeter-service/plug/smoke.sh
docker compose down -v
```

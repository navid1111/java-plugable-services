# Testing Guide and Terminal Commands

This document outlines the commands to run, configure, and test
`post-search-service` behind Kong.

## 1. Environment Setup

Create a root `.env` file with JWT settings shared by `auth-service` and
Kong:

```bash
echo "JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
```

The issuer must match the Kong JWT credential key, because Kong uses the
token's `iss` claim to find the verification secret.

## 2. Managing the Root Stack

The service is scoped under the `post-search` profile. That profile also
starts auth, because all `/post-search` endpoints require JWTs.

**Start the stack:**

```bash
docker compose --profile post-search up --build -d
```

**Check health:**

```bash
docker compose --profile post-search ps
```

Expected healthy services:

- `kong`
- `kong-database`
- `users-db`
- `auth-service`
- `post-search-db`
- `post-search-service`

**Stop without deleting volumes:**

```bash
docker compose --profile post-search down
```

**Stop and delete volumes:**

```bash
docker compose --profile post-search down -v
```

## 3. Configuring Kong

Configure core auth first, then register the protected `/post-search` route:

```bash
./kong/setup-core.sh
./kong/setup-post-search.sh
```

`setup-core.sh` registers:

- `/auth` route
- Kong consumer `springboot-auth`
- HS256 JWT credential matching `JWT_SECRET` and `JWT_ISSUER`

`setup-post-search.sh` registers:

- Kong service `post-search-service`
- protected `/post-search` route
- `jwt` plugin
- rate limiting: 10 requests/minute, 100 requests/hour

## 4. Service-Level Smoke Test

Run the reusable search service smoke test:

```bash
./post-search-service/plug/smoke.sh
```

This test verifies:

- register and login through auth
- missing token returns `401`
- blank query returns `400`
- documents can be indexed
- like count can be updated
- multi-term search uses AND semantics
- `sort=recency` and `sort=likes` return the expected order

## 5. Manual API Testing with Curl

The API is exposed through Kong at `http://localhost:18000`.

### A. Register and Login

```bash
curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'

TOKEN=$(curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

printf 'token length: %s\n' "${#TOKEN}"
```

### B. Confirm Kong Rejects Missing Tokens

```bash
curl -i -s "http://localhost:18000/post-search?q=java"
```

Expected output: `401 Unauthorized`.

### C. Reject Blank Query

```bash
curl -i -s "http://localhost:18000/post-search?q=" \
  -H "Authorization: Bearer $TOKEN"
```

Expected output: `400 Bad Request`.

### D. Index Documents

```bash
curl -i -s -X PUT http://localhost:18000/post-search/documents/tweeter.post/post-1 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "authorUsername": "alice",
    "content": "java spring search example",
    "createdAt": "2026-07-09T00:00:01Z"
  }'

curl -i -s -X PUT http://localhost:18000/post-search/documents/tweeter.post/post-2 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "authorUsername": "alice",
    "content": "java docker search example",
    "createdAt": "2026-07-09T00:00:02Z"
  }'
```

Expected output: `200 OK` with the stored search document snapshot.

### E. Search by Single Term

```bash
curl -i -s "http://localhost:18000/post-search?q=java&sort=recency&pageSize=10" \
  -H "Authorization: Bearer $TOKEN"
```

Expected output: both documents, newest first.

### F. Search by Multiple Terms

```bash
curl -i -s "http://localhost:18000/post-search?q=java%20spring&sort=recency&pageSize=10" \
  -H "Authorization: Bearer $TOKEN"
```

Expected output: only the document containing both `java` and `spring`.

### G. Update Like Count

```bash
curl -i -s -X PUT http://localhost:18000/post-search/documents/tweeter.post/post-1/like-count \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"likeCount":42}'
```

Expected output: `200 OK`, with `likeCount` set to `42`.

### H. Search by Likes

```bash
curl -i -s "http://localhost:18000/post-search?q=java&sort=likes&pageSize=10" \
  -H "Authorization: Bearer $TOKEN"
```

Expected output: highest-like matching document first.

### I. Read an Indexed Document

```bash
curl -i -s http://localhost:18000/post-search/documents/tweeter.post/post-1 \
  -H "Authorization: Bearer $TOKEN"
```

Expected output: `200 OK` for existing documents or `404` for missing ones.

## 6. Final Integration Demo

The final demo composes auth, posts, comments, and search.

### A. Build Images

```bash
docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
docker build -t tweeter-service:latest -f tweeter-service/Dockerfile tweeter-service/
docker build -t comment-service:latest -f comment-service/Dockerfile comment-service/
docker build -t post-search-service:latest -f post-search-service/Dockerfile post-search-service/
```

### B. Start the Demo Stack

```bash
cd examples/post-search-standalone/
echo "JWT_SECRET=super-secret-jwt-key-for-post-search-demo-32-bytes" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
docker compose up -d
```

### C. Configure Kong

```bash
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

../../tweeter-service/plug/kong-setup.sh
../../comment-service/plug/kong-setup.sh
../../post-search-service/plug/kong-setup.sh
```

### D. Run the Final Smoke Test

```bash
./smoke.sh
```

The smoke test proves:

- auth register/login works
- `/post-search` rejects unauthenticated callers
- tweeter creates real posts
- comments attach to `tweeter.post/{postId}`
- post snapshots are indexed into search
- like-count ranking works
- keyword search works by recency and likes

### E. Tear Down

```bash
docker compose down -v
```

## 7. Local Java Verification

Compile the service without running Docker:

```bash
mvn -q -Dmaven.repo.local=/home/navid/java/.m2-tmp -DskipTests package
```

If you want to remove generated output afterward:

```bash
rm -rf post-search-service/target
```

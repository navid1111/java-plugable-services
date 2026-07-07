# Spring Boot + Kong API Gateway

An API gateway setup where **Kong** sits in front of a **Spring Boot** backend and
handles **authentication** (JWT) and **rate limiting** at the edge. Spring Boot
issues the tokens (login against a Postgres users table); Kong verifies them.
Everything runs in Docker.

```
Client ──▶ Kong (:18000) ──▶ Spring Boot app (:8080, internal only)
              │
              ├─ /auth/*   PUBLIC   (register + login -> issues a JWT)
              ├─ /api/*    jwt      (Kong verifies the HS256 token)
              ├─ rate-limiting      (10/min, 100/hour, whole service)
              └─ Postgres           (Kong config store)

Spring Boot ──▶ app-database (Postgres)   users table, BCrypt password hashes
```

**Auth model:** Spring Boot signs HS256 JWTs (`iss=springboot-auth`, `sub=<username>`);
Kong holds a matching jwt credential (same shared secret) and verifies every `/api`
request. The shared secret lives in `docker-compose.yml` (`JWT_SECRET`) and must equal
the one in `kong/setup.sh`.

## Components

| Service          | Port (host) | Purpose                                   |
|------------------|-------------|-------------------------------------------|
| `kong`           | 8000 / 8001 | Proxy (8000) + Admin API (8001)           |
| `kong-database`  | —           | Postgres 16, Kong's config store          |
| `kong-migrations`| —           | One-shot schema bootstrap, then exits     |
| `app`            | — (internal)| Spring Boot backend, reachable via Kong   |

The Spring Boot app is **not** published to the host — the only way in is through
Kong on port 8000. That's the whole point of a gateway.

## Prerequisites

- Docker + Docker Compose
- No local Java/Maven needed — the app is built inside the Docker image.

## Run

```bash
# 1. Build the app image and start the stack
docker compose up --build -d

# 2. Wait until Kong is healthy, then configure the gateway
#    (creates the service, public /auth + protected /api routes, jwt, rate-limiting)
./kong/setup.sh
```

## Test it

```bash
BASE=http://localhost:18000

# 1. No token -> 401
curl -i $BASE/api/hello

# 2. Register a user (public /auth) -> 201
curl -s -X POST $BASE/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'

# 3. Log in -> returns access_token
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

# 4. Call the protected API with the token -> 200, "user":"alice"
curl -i $BASE/api/hello -H "Authorization: Bearer $TOKEN"

# 5. Rate limit -> >10 requests/min returns 429
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    $BASE/api/hello -H "Authorization: Bearer $TOKEN"
done
```

Kong returns `401` for a missing/expired/forged token; Spring returns `401` for a
wrong password. Rate-limit responses include a `RateLimit-Remaining` header.

## Useful admin calls

```bash
curl http://localhost:8001/services            # registered services
curl http://localhost:8001/routes              # routes
curl http://localhost:8001/plugins             # enabled plugins
curl http://localhost:8001/consumers           # API consumers
```

## Tear down

```bash
docker compose down          # stop containers
docker compose down -v       # stop AND wipe Postgres (Kong config) volume
```

## Configuration knobs

- **API key / consumer** — edit `kong/setup.sh` (`API_KEY`, consumer name).
- **Rate limits** — edit the `rate-limiting` `config.minute` / `config.hour` in `kong/setup.sh`.
- **DB credentials** — set in `docker-compose.yml` (inline for local dev; move to a
  `.env` file before deploying anywhere real).

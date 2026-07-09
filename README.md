# Spring Boot + Kong API Gateway

An API gateway setup where **Kong** sits in front of a **Spring Boot** backend and handles **authentication** (JWT) and **rate limiting** at the edge. The `auth-service` issues the tokens (login against a Postgres users table); Kong verifies them for other downstream services.
Everything runs in Docker.

```text
Client ──▶ Kong (:18000)
              │
              ├─ /auth/*   PUBLIC   (register + login + me) ──▶ auth-service (:8080)
              ├─ /posts/*  JWT      (posts + follows + feed) ─▶ tweeter-service (:8080)
              ├─ /comments/* JWT     (generic target comments) ─▶ comment-service (:8080)
              ├─ /post-search/* JWT  (keyword post search) ────▶ post-search-service (:8080)
              ├─ /bookings/* JWT     (generic slot booking) ────▶ booking-service (:8080)
              ├─ /media/* JWT        (image/video attachments) ─▶ media-service (:8080)
              └─ Postgres           (Kong config store)
```

## Service Contract & Plug Kit Layout

Every backend service is packaged as a "plug kit" with a standard contract. The `auth-service` establishes this template:

- **Prefix:** It owns the `/auth` prefix.
- **JWT Verification:** `auth-service` endpoints are public (no Kong jwt plugin, since it handles auth itself). Future services will have their routes protected by `jwt-by-default`.
- **Database:** It has its own dedicated database (`users-db`), completely separate from Kong's config store.
- **Plug Kit Layout:** The service exports a `plug/` directory containing everything needed to integrate it:
  - `compose.plug.yml`: Docker Compose definition for the service and its DB.
  - `kong-setup.sh`: Idempotent script to register the service and its routes with Kong.
  - `smoke.sh`: Script to automatically test the service's happy path through the gateway.

## Prerequisites

- Docker + Docker Compose
- No local Java/Maven needed — the app is built inside the Docker image.

## Run

```bash
# 1. Create a .env file with your JWT secrets
echo "JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789" > .env
echo "JWT_ISSUER=springboot-auth" >> .env

# 2. Build the app image and start the stack
#    Auth only:
docker compose --profile auth up --build -d
#    Auth + tweeter:
docker compose --profile tweeter up --build -d
#    Auth + reusable comments:
docker compose --profile comments up --build -d
#    Auth + post search:
docker compose --profile post-search up --build -d
#    Auth + reusable media:
docker compose --profile media up --build -d
#    Auth + reusable booking (any resource with time slots):
docker compose --profile booking up --build -d

# 3. Wait until Kong is healthy, then configure the gateway
#    (creates the core jwt issuer + delegates to auth-service plug kit)
./kong/setup-core.sh
#    If the tweeter profile is enabled, register /posts too:
./kong/setup-tweeter.sh
#    If the comments profile is enabled, register /comments too:
./kong/setup-comments.sh
#    If the post-search profile is enabled, register /post-search too:
./kong/setup-post-search.sh
#    If the media profile is enabled, register /media too:
./kong/setup-media.sh
#    If the booking profile is enabled, register /bookings too:
./kong/setup-booking.sh
```

## Test it

You can use the provided smoke test script to verify the full register → login → `/auth/me` flow:

```bash
./auth-service/plug/smoke.sh
./tweeter-service/plug/smoke.sh
./comment-service/plug/smoke.sh
./post-search-service/plug/smoke.sh
./media-service/plug/smoke.sh
./booking-service/plug/smoke.sh
```

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
docker compose down -v       # stop AND wipe Postgres volumes
```

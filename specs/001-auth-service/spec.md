# Feature Specification: auth-service

**Feature Branch:** `001-auth-service` | **Created:** 2026-07-07 | **Status:** Draft
**Input:** Extract the existing demo app into a standalone, pluggable auth
service that issues JWTs consumed by every other service on the platform.

## User Scenarios & Testing

### User Story 1 — Register and log in (Priority: P1)

A new user registers with a username and password, then logs in and receives
a token they can use against any protected service on the platform.

**Why this priority:** Every other service depends on tokens existing.

**Independent test:** Register + login through Kong; decode the returned JWT
and verify its claims. No other service needs to exist.

**Acceptance scenarios:**
1. **Given** no existing user `alice`, **When** she POSTs to `/auth/register`
   with a username and password, **Then** she gets `201` and a users-db row
   with a BCrypt hash (never the plaintext).
2. **Given** a registered user, **When** she POSTs correct credentials to
   `/auth/login`, **Then** she receives an HS256 JWT with
   `iss=springboot-auth`, `sub=alice`, and an expiry.
3. **Given** a registered user, **When** she logs in with a wrong password,
   **Then** she gets `401` and no token.
4. **Given** username `alice` exists, **When** someone registers `alice`
   again, **Then** they get `409`.

### User Story 2 — Read my own profile (Priority: P1)

An authenticated user fetches their own identity (id, username) so composed
frontends can render "logged in as…" from the token alone.

**Independent test:** Call `GET /auth/me` with and without a Bearer token.

**Acceptance scenarios:**
1. **Given** a valid Bearer token, **When** GET `/auth/me`, **Then** `200`
   with `{id, username}` matching the token's `sub`.
2. **Given** no/expired/garbage token, **When** GET `/auth/me`, **Then** `401`.

### User Story 3 — Integration demo: plug auth into a separate project (Priority: P2)

A developer starts a brand-new project (own compose file, own Kong), drops in
the auth plug kit, and has working register/login/me with zero auth code of
their own.

**Why this priority:** This is the platform's core claim (Constitution
Art. VII); auth is the kit every other demo will reuse.

**Independent test:** `examples/auth-standalone/` comes up from scratch and
its `smoke.sh` passes without touching auth-service source.

**Acceptance scenarios:**
1. **Given** an empty host project with only Kong + kong-db running,
   **When** the developer adds `auth/plug/compose.plug.yml` and runs
   `kong-setup.sh`, **Then** register → login → `/auth/me` all pass through
   the host's Kong.
2. **Given** the demo passed, **When** the service image is rebuilt from
   unchanged source, **Then** the demo still passes (no demo-specific patches).

### Edge Cases

- Registration with empty/whitespace username or password → `400`.
- Token issued by a different issuer/secret → rejected at `/auth/me`.
- users-db down → `503`-class error, not a hang.

## Requirements

### Functional Requirements

- **FR-001:** Service MUST expose `POST /auth/register` storing BCrypt-hashed
  credentials in its own users-db.
- **FR-002:** Service MUST expose `POST /auth/login` issuing an HS256 JWT
  (`iss=springboot-auth`, `sub=<username>`, `exp`).
- **FR-003:** Service MUST expose `GET /auth/me` returning `{id, username}`,
  validating the token app-side (the `/auth` route is public in Kong).
- **FR-004:** The `/auth` prefix MUST be registered by the service's own plug
  script; Kong's jwt plugin MUST NOT be attached to it.
- **FR-005:** JWT secret and issuer MUST be env-driven and shared with Kong
  configuration (single source: `.env`).
- **FR-006:** Service MUST ship a plug kit per Constitution Art. IV.
- **FR-007:** The demo `/api/hello` endpoint is removed (or lives on as
  `/auth/ping`).

### Key Entities

- **User** — id, username (unique), passwordHash, createdAt. Owned solely by
  this service (Constitution Art. I, VI).

## Success Criteria

- **SC-001:** register → login → `GET /auth/me` passes end-to-end through
  Kong on a clean `docker compose up`.
- **SC-002:** `examples/auth-standalone/smoke.sh` passes with zero service
  code changes (Constitution Art. VII).
- **SC-003:** No other service on the platform contains password or session
  logic (verified by grep in later features' reviews).

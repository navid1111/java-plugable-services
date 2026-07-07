# Implementation Plan: auth-service

**Branch:** `001-auth-service` | **Date:** 2026-07-07
**Spec:** [spec.md](spec.md)

## Summary

Move the existing single demo app into `auth-service/`, strip the demo
endpoint, add `GET /auth/me`, and package the result as the platform's first
plug kit. This feature also performs the one-time monorepo restructure that
every later service builds on.

## Technical Context

- **Language/runtime:** Java 21, Spring Boot 4.1 (already in place)
- **Storage:** PostgreSQL (`users-db`), one container per Constitution Art. I
- **Auth:** BCrypt hashing; HS256 JWT via existing `JwtService`
- **Gateway:** Kong 3.9 DB-backed; `/auth` route public (no jwt plugin)
- **Build/deploy:** existing multi-stage Dockerfile; Docker Compose profiles
- **Testing:** curl-based smoke script through Kong (`plug/smoke.sh`)

## Constitution Check

| Article | Status |
|---------|--------|
| I — one DB per service | ✅ users-db only |
| II — auth at the edge | ✅ this service *is* the issuer; `/auth` route deliberately public |
| III — identity by reference | ✅ owns the canonical user record |
| IV — plug kit | ✅ `auth-service/plug/` created here (first instance of the pattern) |
| V — no service-to-service calls | ✅ none |
| VI — single ownership | ✅ profile data lives here |
| VII — integration demo | ✅ `examples/auth-standalone/` |
| VIII — right-sized | ✅ single node, no cache |

## Project Structure (after this feature)

```
java/
├── docker-compose.yml            # kong + kong-db + auth-service + users-db
├── kong/setup-core.sh            # gateway basics; delegates to auth plug kit
├── auth-service/
│   ├── pom.xml, Dockerfile, src/ # moved from repo root
│   └── plug/
│       ├── compose.plug.yml
│       ├── kong-setup.sh
│       └── smoke.sh
├── examples/auth-standalone/     # separate host project (Art. VII)
│   ├── docker-compose.yml        # own kong + kong-db, image-only auth
│   └── README.md
└── specs/001-auth-service/
```

## Design Decisions

1. **Restructure first, extract second.** The move (`src/` → `auth-service/`)
   is mechanical and verified by the existing green flow before any behavior
   changes; `/auth/me` lands only after the move is proven.
2. **`/auth/me` validates app-side.** The route is public in Kong (login must
   work without a token), so `JwtService` re-validates the Bearer token in
   the controller. This is the one deliberate exception to edge-only
   verification, and it is confined to this service.
3. **Plug kit is the deliverable, wrapper scripts are sugar.** Route
   registration logic lives in `auth-service/plug/kong-setup.sh`
   (parameterized by `KONG_ADMIN_URL`); the repo-root `kong/setup-core.sh`
   just calls it. Host projects call it directly.
4. **Image naming:** `auth-service:local` for now; a registry tag is a
   hardening-phase (006) concern.

## Risks

- Compose path/name churn (`app` → `auth-service`, `app-database` →
  `users-db`) breaking Kong upstream host names — mitigated by running the
  full smoke flow as the restructure's exit gate before touching Java code.

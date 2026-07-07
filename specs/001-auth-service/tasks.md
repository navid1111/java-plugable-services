# Tasks: auth-service

**Input:** [spec.md](spec.md), [plan.md](plan.md)
`[P]` = parallelizable with neighbors. `[USn]` = serves User Story n.

## Phase 1 — Setup (repo restructure)

- [x] T001 Create `auth-service/` and move `src/`, `pom.xml`, `mvnw*`, `.mvn/`,
      `Dockerfile`, `.dockerignore` into it
- [x] T002 Update `docker-compose.yml`: build context `./auth-service`, rename
      service `app` → `auth-service`, `app-database` → `users-db`
- [x] T003 Rename `kong/setup.sh` → `kong/setup-core.sh`; update upstream host
      to `auth-service`
- [x] T004 Add compose profile scaffolding (core services always on; feature
      services behind profiles)
- [x] T005 **Checkpoint:** existing register → login → protected-call flow is
      green through Kong (nothing behavioral changed yet)

## Phase 2 — User Story 1: register & login (P1)

- [x] T006 [US1] Remove `HelloController` `/api/hello` (or convert to
      `/auth/ping`) in `auth-service/src/.../controller/`
- [x] T007 [US1] Add `400` validation (empty username/password) and `409` on
      duplicate username to register endpoint
- [x] T008 [US1] Move JWT secret + issuer to env vars consumed by both the app
      and Kong config (single `.env` source)
- [x] T009 [US1] **Checkpoint:** acceptance scenarios 1–4 of US1 pass via curl
      through Kong

## Phase 3 — User Story 2: /auth/me (P1)

- [x] T010 [US2] Add `GET /auth/me` controller: validate Bearer token via
      `JwtService`, return `{id, username}`; `401` on missing/invalid/expired
- [x] T011 [US2] **Checkpoint:** US2 scenarios pass; garbage/foreign-issuer
      tokens rejected

## Phase 4 — Plug kit (Constitution Art. IV)

- [x] T012 [P] Write `auth-service/plug/compose.plug.yml` (auth-service image +
      users-db, env-driven secret)
- [x] T013 [P] Write `auth-service/plug/kong-setup.sh` — idempotent `/auth`
      route registration, `KONG_ADMIN_URL` parameter, **no jwt plugin**
- [x] T014 [P] Write `auth-service/plug/smoke.sh` — register → login →
      `/auth/me` through whatever Kong the host provides
- [x] T015 Refactor `kong/setup-core.sh` to delegate to the plug kit
- [x] T016 Document the service contract in README (prefix, jwt-by-default for
      others, own DB, plug kit layout) — this is the template every later
      service copies

## Phase 5 — User Story 3: integration demo (P2, Constitution Art. VII)

- [x] T017 [US3] Create `examples/auth-standalone/docker-compose.yml`: fresh
      Kong + kong-db + auth via `compose.plug.yml` include — **image only, no
      build context into service source**
- [x] T018 [US3] Write `examples/auth-standalone/README.md`: the exact
      commands a stranger runs (build image → compose up → kong-setup →
      smoke)
- [x] T019 [US3] **Checkpoint (feature exit):** `smoke.sh` passes in the
      standalone project with zero service-code changes → SC-002 met

## Dependencies

- Phase 1 blocks everything (T005 is the gate).
- Phases 2 and 3 are sequential (shared controller layer), then Phase 4
  tasks T012–T014 can run in parallel.
- Phase 5 requires Phase 4 complete.

# Tasks: comment-service

**Input:** [spec.md](spec.md), [plan.md](plan.md)
**Prerequisite:** feature 001 complete (tokens available). No target service
is required by comment-service itself.

## Phase 1 - Setup

- [x] T001 Scaffold `comment-service/` from the existing Spring Boot service
      pattern; own artifactId, Dockerfile, health endpoint, port 8080
      internal
- [x] T002 [P] Add `comments-db` + `comment-service` to root compose under
      profile `comments`; add profile `comments` to `users-db` and
      `auth-service` so tokens work when only comments are enabled
- [x] T003 [P] Copy JWT-decode helper into
      `comment-service/src/.../security/`

## Phase 2 - User Story 1: create and read comments (P1)

- [x] T004 [US1] Comment entity + repository (`comments` table; indexes on
      `(target_type, target_id, created_at DESC, id DESC)` and
      `(author_username, created_at DESC, id DESC)`)
- [x] T005 [US1] `POST /comments/targets/{targetType}/{targetId}` with
      author from token `sub`; trim content; `400` on empty, oversize, or
      invalid target key
- [x] T006 [US1] `GET /comments/{id}` with `404` for missing comments
- [x] T007 [US1] **Checkpoint:** create -> get by id passes direct-to-service
      and through Kong after T013

## Phase 3 - User Story 2: list comments for a target (P1)

- [x] T008 [US2] Repository query for newest-first target comments with
      composite cursor `(created_at, id)` and page-size clamp
- [x] T009 [US2] `GET /comments/targets/{targetType}/{targetId}?cursor=&pageSize=`
      returning `{ items, nextCursor }`
- [x] T010 [US2] **Checkpoint:** cursor walk is duplicate-free and gap-free,
      including identical timestamp fixture; unrelated targets do not leak
      comments into each other; empty target returns empty page

## Phase 4 - User Story 3: delete own comment (P1)

- [x] T011 [US3] `DELETE /comments/{id}`; owner gets `204`, non-owner gets
      `403`, missing id gets `404`
- [x] T012 [US3] **Checkpoint:** deleted comments do not appear in
      target comment pages and cannot be fetched by id

## Phase 5 - Plug kit + gateway

- [x] T013 [P] `comment-service/plug/kong-setup.sh` - `/comments` route +
      jwt plugin + rate limiting, idempotent, `KONG_ADMIN_URL` param
- [x] T014 [P] `comment-service/plug/compose.plug.yml` - image +
      `comments-db`
- [x] T015 [P] `comment-service/plug/smoke.sh` - register/login users via
      auth, create/list/get/delete comments on at least two target namespaces
      via comment-service, prove unauthorized `401`, non-owner delete `403`,
      and target isolation
- [x] T016 Thin wrapper `kong/setup-comments.sh` delegating to the plug kit

## Phase 6 - User Story 4: integration demo (P2, Art. VII)

- [x] T017 [US4] `examples/comments-standalone/docker-compose.yml`: fresh
      Kong + auth plug kit + comment plug kit, images only
- [x] T018 [US4] `examples/comments-standalone/README.md` with exact commands
- [x] T019 [US4] **Checkpoint (feature exit):** standalone smoke green, zero
      service-code changes -> SC-003

## Dependencies

- T001 blocks all service code.
- T003 blocks authenticated create/delete endpoints.
- Phase 2 -> Phase 3 -> Phase 4 are sequential.
- Phase 5 tasks can run in parallel after Phase 4.
- Phase 6 is last and is the done gate.

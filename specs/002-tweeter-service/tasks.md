# Tasks: tweeter-service

**Input:** [spec.md](spec.md), [plan.md](plan.md)
**Prerequisite:** feature 001 complete (auth plug kit exists).

## Phase 1 — Setup

- [x] T001 Scaffold `tweeter-service/` (copy auth-service pom/Dockerfile
      pattern; own artifactId; port 8080 internal)
- [x] T002 [P] Add `posts-db` + `tweeter-service` to root compose under
      profile `tweeter`
- [x] T003 [P] Copy JWT-decode helper (reads `sub` only) into
      `tweeter-service/src/.../security/`

## Phase 2 — User Story 1: posts CRUD (P1)

- [x] T004 [US1] Post entity + repository (`posts` table, index
      `(author_username, created_at DESC)`)
- [x] T005 [US1] `POST /posts` (author from token `sub`; `400` on empty/oversize
      content), `GET /posts/{id}`, `GET /posts?author=`
- [x] T006 [US1] **Checkpoint:** US1 scenarios pass through Kong (after T012)
      or direct-to-service pre-gateway

## Phase 3 — User Story 2: follow graph (P1)

- [x] T007 [US2] Follow entity + repository; unique (follower, followee);
      `ON CONFLICT DO NOTHING`
- [x] T008 [US2] `PUT /posts/users/{username}/follow` + `DELETE`; `400` on
      self-follow; both idempotent
- [x] T009 [US2] **Checkpoint:** follow/unfollow/re-follow scenarios pass

## Phase 4 — User Story 3: feed (P1)

- [x] T010 [US3] Feed query with composite cursor `(created_at, id)` —
      single SQL, fan-out on read, `pageSize` clamp
- [x] T011 [US3] **Checkpoint:** interleaved order, cursor walk with no
      duplicates/gaps (including identical-timestamp fixture), empty feed case

## Phase 5 — Plug kit + gateway

- [x] T012 [P] `tweeter-service/plug/kong-setup.sh` — `/posts` route + jwt
      plugin + rate limiting, idempotent, `KONG_ADMIN_URL` param
- [x] T013 [P] `tweeter-service/plug/compose.plug.yml` (image + posts-db,
      profile `tweeter`)
- [x] T014 [P] `tweeter-service/plug/smoke.sh` — full two-user scenario
      (register×2 via auth, follow, post, feed, paging, 401 check)
- [x] T015 Thin wrapper `kong/setup-tweeter.sh` delegating to the plug kit

## Phase 6 — User Story 4: integration demo (P2, Art. VII)

- [x] T016 [US4] `examples/tweeter-standalone/docker-compose.yml`: fresh Kong
      + auth plug kit + tweeter plug kit, images only
- [x] T017 [US4] `examples/tweeter-standalone/README.md` with exact commands
- [x] T018 [US4] **Checkpoint (feature exit):** standalone `smoke.sh` green,
      zero service-code changes → SC-002

## Dependencies

- T001 blocks all; T003 blocks T005.
- Phases 2→3→4 sequential (each story builds on prior data).
- Phase 5 tasks parallel after Phase 4; Phase 6 last.

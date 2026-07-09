# Tasks: post-search-service

**Input:** [spec.md](spec.md), [plan.md](plan.md)
**Prerequisite:** feature 001 complete (tokens), feature 002 complete
(posts), and feature 007 complete (comments) for the final integration demo.

## Phase 1 - Setup

- [x] T001 Scaffold `post-search-service/` from the existing Spring Boot
      service pattern; own artifactId, Dockerfile, health endpoint, port
      8080 internal
- [x] T002 [P] Add `post-search-db` + `post-search-service` to root compose
      under profile `post-search`; ensure auth is enabled for that profile
- [x] T003 [P] Copy JWT-decode helper into
      `post-search-service/src/.../security/`

## Phase 2 - User Story 1: ingest post documents (P1)

- [x] T004 [US1] Entities + repositories: SearchDocument unique
      `(target_type, target_id)` and SearchTermEntry unique
      `(term, document_id)`
- [x] T005 [US1] Tokenizer utility: lowercase ASCII terms, split on
      non-alphanumeric, dedupe terms per document
- [x] T006 [US1] `PUT /post-search/documents/{targetType}/{targetId}` -
      validate target key, authorUsername, content, createdAt; idempotently
      replace document snapshot and term rows
- [x] T007 [US1] **Checkpoint:** direct-to-service ingestion creates one
      document and expected term rows; repeat ingestion updates without
      duplicates

## Phase 3 - User Story 2: keyword search (P1)

- [x] T008 [US2] Query normalization for `q`; blank/empty-term query -> `400`
- [x] T009 [US2] Repository query for AND-semantics multi-term search using
      term rows and grouped document matches
- [x] T010 [US2] `GET /post-search?q=&sort=recency&cursor=&pageSize=`
      returning `{ items, nextCursor }`
- [x] T011 [US2] **Checkpoint:** single-term, multi-term, no-match, and bad
      query scenarios pass

## Phase 4 - User Story 3: sort and cursor paging (P1)

- [x] T012 [US3] `recency` sort cursor `(created_at, document_id)` with
      newest-first stable paging
- [x] T013 [US3] `likes` sort cursor
      `(like_count, created_at, document_id)` with highest-like stable paging
- [x] T014 [US3] Page-size clamp and invalid cursor/sort validation
- [x] T015 [US3] **Checkpoint:** both sort orders page duplicate-free and
      gap-free, including identical timestamp/like-count fixtures

## Phase 5 - User Story 4: like-count ranking signal (P2)

- [x] T016 [US4] `PUT /post-search/documents/{targetType}/{targetId}/like-count`
      updates likeCount; missing document -> `404`; negative count -> `400`
- [x] T017 [US4] **Checkpoint:** updating like count changes `sort=likes`
      order without changing document terms

## Phase 6 - Plug kit + gateway

- [x] T018 [P] `post-search-service/plug/kong-setup.sh` - `/post-search`
      route + jwt plugin + rate limiting, idempotent, `KONG_ADMIN_URL` param
- [x] T019 [P] `post-search-service/plug/compose.plug.yml` - image +
      `post-search-db`, profile `post-search`
- [x] T020 [P] `post-search-service/plug/smoke.sh` - service-level smoke:
      register/login via auth, ingest documents, search by keyword, update
      like count, verify `401`, bad query `400`, and both sort orders
- [x] T021 Thin wrapper `kong/setup-post-search.sh` delegating to the plug kit

## Phase 7 - User Story 5: final integration demo (P2, Art. VII)

- [x] T022 [US5] `examples/post-search-standalone/docker-compose.yml`: fresh
      Kong + auth plug kit + tweeter plug kit + comment plug kit +
      post-search plug kit, images only
- [x] T023 [US5] `examples/post-search-standalone/README.md` with exact
      commands
- [x] T024 [US5] `examples/post-search-standalone/smoke.sh` final test:
      auth register/login -> create post via `/posts` -> comment on
      `tweeter.post/{postId}` via `/comments` -> index post via
      `/post-search` -> update like count -> search by keyword sorted by
      recency and likes -> prove unauthorized search is `401`
- [x] T025 [US5] **Checkpoint (feature exit):** final smoke green with
      auth + post + comment + post-search, zero service-code changes -> SC-004

## Dependencies

- T001 blocks all service code.
- T003 blocks protected controller endpoints.
- Phases 2 -> 3 -> 4 are sequential.
- Phase 5 can start after T006 but exits after Phase 4.
- Phase 6 can run after Phase 5.
- Phase 7 is last and is the done gate.

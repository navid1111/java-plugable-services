# Tasks: media-service

**Input:** [spec.md](spec.md), [plan.md](plan.md), [../../media_test.md](../../media_test.md)
**Prerequisite:** feature 001 complete for JWTs; features 002, 007, and 008
complete for the final auth + post + comment + search + media integration
demo.

## Phase 1 - Planning and safe environment setup

- [ ] T001 Add committed `.env.example` with Cloudinary placeholders and
      git-ignore real `.env`
- [ ] T002 Add `media_test.md` explaining exactly when to create `.env`
      before Cloudinary testing
- [ ] T003 Decide Cloudinary SDK vs signed HTTP API after checking official
      docs during implementation

## Phase 2 - Service scaffold

- [ ] T004 Scaffold `media-service/` from the existing Spring Boot service
      pattern; own artifactId, Dockerfile, health endpoint, port 8080
      internal
- [ ] T005 Add `media-db` + `media-service` to root compose under profile
      `media`; ensure auth is enabled for that profile
- [ ] T006 Copy JWT-decode helper into `media-service/src/.../security/`
- [ ] T007 Add provider configuration binding for Cloudinary env vars and
      media size/format limits

## Phase 3 - User Story 2: upload image/video to any target (P1)

- [ ] T008 [US2] Entity + repository: `MediaAsset` with target paging index
      and unique Cloudinary `publicId`
- [ ] T009 [US2] Target key validation matching reusable service convention
- [ ] T010 [US2] Multipart validation: required file, allowed image/video
      formats, image/video size limits, optional caption/altText limits
- [ ] T011 [US2] Cloudinary upload adapter returning publicId, secureUrl,
      resourceType, format, bytes, dimensions, duration, thumbnailUrl
- [ ] T012 [US2] `POST /media/targets/{targetType}/{targetId}` uploads to
      Cloudinary and stores metadata
- [ ] T013 [US2] **Checkpoint:** image upload and video upload work with real
      `.env`; bad target/type/size cases fail clearly

## Phase 4 - User Story 3: read media by target (P1)

- [ ] T014 [US3] Cursor model `(created_at, id)` for newest-first media
      listing
- [ ] T015 [US3] `GET /media/targets/{targetType}/{targetId}` returning
      `{ items, nextCursor }`
- [ ] T016 [US3] Page-size clamp and invalid cursor validation
- [ ] T017 [US3] **Checkpoint:** target isolation and cursor paging pass

## Phase 5 - User Story 4: read/delete asset (P2)

- [ ] T018 [US4] `GET /media/{id}` returns metadata or `404`
- [ ] T019 [US4] `DELETE /media/{id}` enforces uploader-only delete
- [ ] T020 [US4] Cloudinary destroy call + metadata delete/tombstone
- [ ] T021 [US4] **Checkpoint:** non-owner delete `403`; owner delete cleans
      test asset or reports cleanup failure

## Phase 6 - Plug kit + gateway

- [ ] T022 `media-service/plug/kong-setup.sh` - `/media` route + jwt plugin
      + rate limiting, idempotent, `KONG_ADMIN_URL` param
- [ ] T023 `media-service/plug/compose.plug.yml` - image + `media-db`,
      profile `media`, Cloudinary env passthrough
- [ ] T024 `media-service/plug/smoke.sh` - register/login via auth, upload
      image, optionally upload video via `MEDIA_SMOKE_VIDEO_PATH`, list by
      target, delete uploaded assets, verify `401`
- [ ] T025 Thin wrapper `kong/setup-media.sh` delegating to the plug kit

## Phase 7 - User Story 5: final integration demo (P2, Art. VII)

- [ ] T026 [US5] `examples/media-standalone/docker-compose.yml`: fresh Kong
      + auth plug kit + tweeter plug kit + comment plug kit + post-search
      plug kit + media plug kit
- [ ] T027 [US5] `examples/media-standalone/README.md` with exact `.env`
      and Cloudinary credential instructions
- [ ] T028 [US5] `examples/media-standalone/smoke.sh`: auth register/login
      -> create post -> comment on post -> upload image to
      `tweeter.post/{postId}` -> index/search post -> upload media to
      `comment.comment/{commentId}` -> list both targets -> delete assets
- [ ] T029 [US5] **Checkpoint (feature exit):** final smoke green with auth +
      post + comment + search + media and no service-code changes -> SC-005

## Dependencies

- T001-T003 happen before implementation and before any Cloudinary testing.
- T004 blocks all service code.
- T006 blocks protected controller endpoints.
- T007 blocks upload.
- Phases 3 -> 4 -> 5 are sequential.
- Phase 6 can run after Phase 5.
- Phase 7 is last and is the done gate.

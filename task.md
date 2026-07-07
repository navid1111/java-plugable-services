# Tasks — dashboard

> Spec-Kit layout: per-feature task lists (T001…) live in
> `specs/NNN-*/tasks.md`. This file tracks feature-level progress only.
> A feature is ☑ **only when its integration demo passes**
> (Constitution Art. VII) — not when its code works in this repo.

## Feature progress

- [x] **001 auth-service** — [tasks](specs/001-auth-service/tasks.md)
  - [x] Phase 1 — repo restructure (T001–T005)
  - [x] US1 register & login (T006–T009)
  - [x] US2 `/auth/me` (T010–T011)
  - [x] Plug kit + service contract in README (T012–T016)
  - [x] 🔌 Integration demo `examples/auth-standalone/` (T017–T019)
- [ ] **002 tweeter-service** — [tasks](specs/002-tweeter-service/tasks.md)
  - [ ] Setup + posts CRUD (T001–T006)
  - [ ] Follow graph (T007–T009)
  - [ ] Cursor feed (T010–T011)
  - [ ] Plug kit (T012–T015)
  - [ ] 🔌 Integration demo `examples/tweeter-standalone/` (T016–T018)
- [ ] **003 whatsapp-service** — [tasks](specs/003-whatsapp-service/tasks.md)
  - [ ] ⚠️ Risk spike first: WS upgrade + jwt through Kong (T002)
  - [ ] Chats + history REST (T004–T007)
  - [ ] Realtime path (T008–T011)
  - [ ] Offline delivery + cleanup (T012–T014)
  - [ ] Plug kit (T015–T018)
  - [ ] 🔌 Integration demo `examples/chat-standalone/` (T019–T021)
- [ ] **004 turf-service** — [tasks](specs/004-turf-service/tasks.md)
  - [ ] Venues & slots (T001–T005)
  - [ ] Booking + concurrent conflict proof (T006–T009)
  - [ ] Mine/cancel (T010–T012)
  - [ ] Plug kit (T013–T016)
  - [ ] 🔌 Integration demo `examples/turf-standalone/` + cost recorded
        (T017–T018)
- [ ] **005 composition demo** — [tasks](specs/005-composition-facebook/tasks.md)
  - [ ] One-command assembly (T001–T003)
  - [ ] Single-login static page (T004–T005)
  - [ ] Zero-diff proof + record (T006–T007)
- [ ] **006 hardening** — [tasks](specs/006-hardening/tasks.md)
  - [ ] Secrets → `.env` (T001–T003)
  - [ ] HTTPS at Kong (T004–T006)
  - [ ] RS256 decision (T007–T008)
  - [ ] decK evaluation (T009)
  - [ ] 🔁 All standalone demos re-pass (T010)

## Done (pre-plan foundation)

- [x] Kong 3.9 DB-backed + Postgres in Docker Compose
- [x] Spring Boot app multi-stage Docker build (no local JDK needed)
- [x] Edge auth: HS256 JWT issued by Spring, verified by Kong jwt plugin
- [x] Register/login with BCrypt + Postgres users table
- [x] Rate limiting 10/min at gateway; 401/200/429 flows verified end-to-end
- [x] Layered package structure (controller/service/repository/model/config)

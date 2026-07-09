# Roadmap — from single demo app to pluggable services

> Spec-Kit layout: this file is the cross-feature roadmap. Per-feature
> implementation plans live in `specs/NNN-*/plan.md`; binding rules in
> [.specify/memory/constitution.md](.specify/memory/constitution.md).

Features are ordered so every milestone ends with a **working, testable
stack**, and every service ends with its **standalone integration demo**
(Constitution Art. VII) — the proof of modularity the platform exists for.

## Order & milestones

| Order | Feature | Plan | Exit gate (summary) |
|-------|---------|------|---------------------|
| 1 | 001 auth-service | [plan](specs/001-auth-service/plan.md) | register → login → `/auth/me` through Kong **and** `examples/auth-standalone/` smoke green |
| 2 | 002 tweeter-service | [plan](specs/002-tweeter-service/plan.md) | two-user feed scenario through Kong **and** `examples/tweeter-standalone/` green |
| 3 | 003 whatsapp-service | [plan](specs/003-whatsapp-service/plan.md) | realtime + offline replay through Kong **and** `examples/chat-standalone/` green (incl. WS) |
| 4 | 004 turf-service | [plan](specs/004-turf-service/plan.md) | concurrent no-double-booking proof **and** `examples/turf-standalone/` green; wall-clock cost recorded |
| 5 | 005 composition demo | [spec](specs/005-composition-facebook/spec.md) | tweeter + chat composed with **zero service diffs**, one login token |
| 6 | 006 hardening | [spec](specs/006-hardening/spec.md) | secrets out of repo, HTTPS smoke green, **all standalone demos re-pass** |
| 7 | 007 comment-service | [plan](specs/007-comment-service/plan.md) | two-user generic target comment scenario through Kong **and** `examples/comments-standalone/` green |
| 8 | 008 post-search-service | [plan](specs/008-post-search-service/plan.md) | keyword post search through Kong **and** `examples/post-search-standalone/` green with auth + post + comment + post-search |
| 9 | 009 media-service | [plan](specs/009-media-service/plan.md) | Cloudinary image/video upload through Kong **and** `examples/media-standalone/` green with auth + post + comment + media |
| 10 | 010 leetcode-service | [plan](specs/010-leetcode-service/plan.md) | dynamic execution through Kong **and** `examples/leetcode-standalone/` green with auth + leetcode |

## The repeating service pattern

001 establishes it, 002 proves it, 003 stresses it (WebSockets), 004 times
it:

1. Scaffold service + own DB + compose profile
2. Build user stories inside one Kong path prefix, identity from `sub`
3. Ship the plug kit (`plug/compose.plug.yml`, `plug/kong-setup.sh`,
   `plug/smoke.sh`)
4. **Integration demo**: mount the kit in a fresh host project under
   `examples/<service>-standalone/` — done means integrated elsewhere

## Cross-feature risks

- **Kong + WebSocket upgrade + jwt** (feature 003): spiked as T002, first
  task of the feature; fallback = token-in-query validated app-side.
- **JWT-decode helper drift**: accepted duplication for 2–3 services;
  a shared library only if behavior actually diverges.
- **Plug-kit compose includes**: `compose.plug.yml` fragments must work both
  in the root compose (profiles) and in host projects (`include:` /
  copy-paste) — settle the mechanism in 001 before 002 copies it.
- **Profile data pull**: services only need `username` (in the token). If
  richer profiles emerge, add `GET /auth/users/{username}` to 001 rather
  than duplicating data (Art. III/VI).

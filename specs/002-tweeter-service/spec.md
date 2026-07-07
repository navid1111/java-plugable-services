# Feature Specification: tweeter-service

**Feature Branch:** `002-tweeter-service` | **Created:** 2026-07-07 | **Status:** Draft
**Input:** First pluggable resource service — posts, follow graph, and a
reverse-chronological feed, with **zero auth code** (proves the platform
pattern). Interview-scale evolution documented in
[docs/architecture/fb-news-feed.md](../../docs/architecture/fb-news-feed.md).

## User Scenarios & Testing

### User Story 1 — Create and read posts (Priority: P1)

An authenticated user publishes short posts; anyone authenticated can read a
post by id or list a user's posts.

**Independent test:** One user, no follows: create → get by id → list by
author, all through Kong with a Bearer token.

**Acceptance scenarios:**
1. **Given** a valid token for `alice`, **When** she POSTs `/posts` with
   content, **Then** `201` with the post; `authorUsername=alice` comes from
   the token's `sub`, **not** from the request body.
2. **Given** an existing post, **When** GET `/posts/{id}`, **Then** `200`
   with content, author, createdAt.
3. **Given** posts by `alice`, **When** GET `/posts?author=alice`, **Then**
   they return newest-first.
4. **Given** no/invalid token, **When** any `/posts` call is made, **Then**
   Kong returns `401` — the request never reaches the service.

### User Story 2 — Follow and unfollow (Priority: P1)

A user follows another user to subscribe to their posts, and can undo it.

**Acceptance scenarios:**
1. **Given** users `alice` and `bob`, **When** alice PUTs
   `/posts/users/bob/follow`, **Then** `200` and the follow edge exists.
2. **Given** alice follows bob, **When** she DELETEs the follow, **Then**
   bob's posts stop appearing in her feed.
3. **Given** alice already follows bob, **When** she follows again, **Then**
   the operation is idempotent (no duplicate edge, no error).

### User Story 3 — Reverse-chronological feed with paging (Priority: P1)

A user reads a feed of posts from everyone they follow, newest first, and
pages through it with a cursor.

**Independent test:** Two users, one follow edge, several posts with distinct
timestamps; walk the feed with a small `pageSize`.

**Acceptance scenarios:**
1. **Given** alice follows bob and carol, **When** GET
   `/posts/feed?pageSize=10`, **Then** their posts interleave newest-first;
   alice's own posts are not required to appear.
2. **Given** a first page was returned with `nextCursor`, **When** GET
   `/posts/feed?cursor=<nextCursor>`, **Then** strictly older posts return,
   no duplicates, no gaps.
3. **Given** alice follows nobody, **Then** the feed is an empty list, `200`.

### User Story 4 — Integration demo: plug tweeter into a separate project (Priority: P2)

A developer adds "posts + follows + feed" to their own Kong-fronted project
by dropping in the tweeter plug kit (plus the auth plug kit for tokens).

**Independent test:** `examples/tweeter-standalone/` — own Kong, auth kit +
tweeter kit, `smoke.sh` runs the full two-user feed scenario.

**Acceptance scenarios:**
1. **Given** a fresh host project with Kong, **When** auth and tweeter plug
   kits are composed in and both `kong-setup.sh` scripts run, **Then** the
   US1–US3 flows pass through the host's Kong.
2. **Given** the demo passed, **Then** zero lines of tweeter-service source
   were modified (Constitution Art. VII).

### Edge Cases

- Post content empty or > max length → `400`.
- Follow yourself → `400`. Follow a username that never posted → allowed
  (identity by reference; no cross-service existence check — Art. III/V).
- Two posts with identical `createdAt` at a page boundary → cursor must not
  drop or duplicate either (tie-break by id).

## Requirements

### Functional Requirements

- **FR-001:** All endpoints live under `/posts`; Kong applies jwt + rate
  limiting to the whole prefix (service contains **no** auth code beyond
  decoding `sub` from the already-verified token).
- **FR-002:** `POST /posts`, `GET /posts/{id}`, `GET /posts?author=` for CRUD.
- **FR-003:** `PUT /posts/users/{username}/follow` and `DELETE` counterpart;
  idempotent both ways.
- **FR-004:** `GET /posts/feed?cursor=&pageSize=` — fan-out **on read**
  (query followees, then their posts), cursor = oldest seen `createdAt`
  (+ id tie-break). No precomputation (Constitution Art. VIII).
- **FR-005:** Owns `posts-db`; stores `authorUsername` only — never profile
  copies (Art. I, III).
- **FR-006:** Ships a plug kit per Art. IV under compose profile `tweeter`.

### Key Entities

- **Post** — id, authorUsername, content, createdAt.
- **Follow** — followerUsername, followeeUsername, createdAt; unique pair.

## Success Criteria

- **SC-001:** Two-user scenario (follow → post → feed order → paging → 401
  without token) passes through Kong on `--profile tweeter`.
- **SC-002:** `examples/tweeter-standalone/smoke.sh` passes with zero service
  code changes.
- **SC-003:** `grep -ri password auth` style review finds no credential or
  session logic in this service (Art. II).
- **SC-004:** Feed pages are duplicate-free and gap-free across boundaries
  (edge-case test with identical timestamps).

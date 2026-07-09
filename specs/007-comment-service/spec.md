# Feature Specification: comment-service

**Feature Branch:** `007-comment-service` | **Created:** 2026-07-09 | **Status:** Done
**Input:** Add reusable comments that can attach to any product resource by
reference: a tweeter post today, a YouTube video later, a turf venue if the
product wants it. This is **not** Facebook Live comments: no live video feed,
no realtime broadcast, no WebSocket/SSE, no replies, and no reactions.

## User Scenarios & Testing

### User Story 1 - Create and read target comments (Priority: P1)

An authenticated user comments on any commentable target, and anyone
authenticated can read that comment by id.

**Independent test:** Register/login two users, then create and read comments
on two unrelated target keys, for example `tweeter.post/123` and
`youtube.video/abc123`, through `comment-service` via Kong.

**Acceptance scenarios:**
1. **Given** a valid token for `bob`, **When** he POSTs
   `/comments/targets/{targetType}/{targetId}` with content, **Then** `201`
   with the comment; `authorUsername=bob` comes from the token's `sub`, not
   from the request body.
2. **Given** an existing comment, **When** GET `/comments/{id}`, **Then**
   `200` with id, targetType, targetId, authorUsername, content, createdAt.
3. **Given** no/invalid token, **When** any `/comments` call is made,
   **Then** Kong returns `401` and the request never reaches the service.

### User Story 2 - Page comments for a target (Priority: P1)

A user opens a commentable resource and sees comments for that target,
newest first, with cursor paging to older comments.

**Independent test:** Create several comments on one target with distinct and
same timestamps; create comments on another target to prove isolation; walk
pages using a small `pageSize`.

**Acceptance scenarios:**
1. **Given** a target has comments from multiple users, **When** GET
   `/comments/targets/{targetType}/{targetId}?pageSize=10`, **Then** comments return
   newest-first and include a `nextCursor` when more exist.
2. **Given** the first page returns `nextCursor`, **When** GET
   `/comments/targets/{targetType}/{targetId}?cursor=<nextCursor>`, **Then**
   strictly older comments return with no duplicates and no gaps.
3. **Given** target `tweeter.post/123` has comments and target
   `youtube.video/abc123` has comments, **When** either target is queried,
   **Then** only that target's comments return.
4. **Given** a target has no comments, **Then** the response is `200` with
   an empty `items` list and `nextCursor=null`.

### User Story 3 - Delete my own comment (Priority: P1)

A user can remove a comment they wrote, but cannot delete another user's
comment.

**Acceptance scenarios:**
1. **Given** bob created a comment, **When** bob DELETEs `/comments/{id}`,
   **Then** `204` and the comment no longer appears in target comment pages.
2. **Given** alice did not create bob's comment, **When** alice DELETEs it,
   **Then** `403`.
3. **Given** the comment id does not exist, **When** DELETE `/comments/{id}`,
   **Then** `404`.

### User Story 4 - Integration demo: plug comments into separate products (Priority: P2)

A developer adds comments to a Kong-fronted product by mounting the auth and
comment plug kits. Product services only need to agree on target keys; the
comment service does not import their code, call their APIs, or read their
databases.

**Independent test:** `examples/comments-standalone/` uses auth for tokens
and comment-service for generic target comments. The smoke test comments on
two target namespaces, such as `tweeter.post` and `youtube.video`, with zero
service-code changes.

**Acceptance scenarios:**
1. **Given** a fresh host project, **When** auth + comment plug kits are
   composed in and their `kong-setup.sh` scripts run, **Then** US1-US3 flows
   pass through the host Kong.
2. **Given** the demo passed, **Then** zero lines of comment-service,
   auth-service, or any target service source were modified.

### Edge Cases

- Comment content empty or longer than 500 characters -> `400`.
- Blank, malformed, or too-long `targetType` -> `400`.
- Blank or too-long `targetId` -> `400`.
- Invalid cursor -> `400`.
- Two comments with identical `createdAt` at a page boundary must not drop
  or duplicate either comment; tie-break by id.
- Comment-service stores target references as strings and does not validate
  whether the external target exists in v1. Strict target existence
  enforcement is deferred until there is an explicit event-backed target
  reference contract.

## Requirements

### Functional Requirements

- **FR-001:** All endpoints live under `/comments`; Kong applies jwt + rate
  limiting to the whole prefix. The service contains zero auth code beyond
  decoding `sub` from the already-verified token.
- **FR-002:** `POST /comments/targets/{targetType}/{targetId}` creates a
  comment with author from JWT `sub`; request body only includes comment
  content.
- **FR-003:** `GET /comments/{id}` reads a single comment; missing comments
  return `404`.
- **FR-004:** `GET /comments/targets/{targetType}/{targetId}?cursor=&pageSize=`
  returns comments for a target newest-first with a composite cursor
  `(createdAt, id)`.
- **FR-005:** `DELETE /comments/{id}` deletes only the caller's own comment;
  non-owners receive `403`.
- **FR-006:** Owns `comments-db`; stores `targetType`, `targetId`, and
  `authorUsername` only. It never reads another service's database or
  profile data.
- **FR-007:** Ships a plug kit under compose profile `comments`.

### Key Entities

- **CommentTarget** - targetType, targetId. Examples: `tweeter.post/123`,
  `youtube.video/abc123`.
- **Comment** - id, targetType, targetId, authorUsername, content, createdAt.

## Success Criteria

- **SC-001:** A two-user scenario passes through Kong: bob comments on
  `tweeter.post/123`, alice comments on `youtube.video/abc123`, each target
  lists only its own comments, and bob deletes his own comment.
- **SC-002:** Comment pages are duplicate-free and gap-free across cursor
  boundaries, including identical timestamp fixtures.
- **SC-003:** `examples/comments-standalone/smoke.sh` passes with zero
  service-code changes.
- **SC-004:** Review confirms no direct database access to target-service
  databases and no HTTP calls from comment-service to target services.

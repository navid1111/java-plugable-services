# Feature Specification: media-service

**Feature Branch:** `009-media-service` | **Created:** 2026-07-09 | **Status:** Draft
**Input:** Add an independent Cloudinary-backed media service so products can
attach images or videos to posts, comments, or any future target resource.

## User Scenarios & Testing

### User Story 1 - Configure Cloudinary safely before testing (Priority: P1)

An operator can prepare local Cloudinary credentials without committing
secrets.

**Independent test:** Copy `.env.example` to `.env`, fill Cloudinary values,
start the media profile, and confirm no real secrets appear in tracked files.

**Acceptance scenarios:**
1. **Given** a fresh clone, **When** the operator copies `.env.example` to
   `.env`, fills `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, and
   `CLOUDINARY_API_SECRET`, **Then** media smoke tests can run locally.
2. **Given** `.env` contains real credentials, **Then** `.env` is ignored by
   git and only `.env.example` is committed.
3. **Given** Cloudinary credentials are missing, **Then** media upload returns
   a clear configuration error and the service does not store a database row.

### User Story 2 - Upload image or video to any target (Priority: P1)

An authenticated user attaches media to a generic target reference.

**Independent test:** Upload a small image to `tweeter.post/post_123` and a
small video to `comment.comment/comment_456` without running tweeter or
comment services.

**Acceptance scenarios:**
1. **Given** Alice has a valid token, **When** she uploads an image to
   `/media/targets/tweeter.post/post_123`, **Then** the file is uploaded to
   Cloudinary and metadata is stored in `media-db`.
2. **Given** Alice has a valid token, **When** she uploads a video to
   `/media/targets/comment.comment/comment_456`, **Then** the video metadata
   is stored with the comment target reference.
3. **Given** the target key is malformed or the file type is unsupported,
   **Then** upload returns `400`.
4. **Given** the file is larger than the configured image/video limit,
   **Then** upload returns `413` or `400` with a clear error.

### User Story 3 - Read media by target (Priority: P1)

A product can render all media attached to a post, comment, or future target.

**Independent test:** Upload multiple media assets to one target and one asset
to another target; list the first target and verify isolation plus cursor
paging.

**Acceptance scenarios:**
1. **Given** a target has two media assets, **When** GET
   `/media/targets/tweeter.post/post_123`, **Then** both assets return
   newest-first.
2. **Given** media exists for another target, **Then** it does not appear in
   the first target's response.
3. **Given** `pageSize` is small, **Then** the response includes `nextCursor`
   and the next page returns older assets without duplicates.

### User Story 4 - Read and delete an uploaded asset (Priority: P2)

An authenticated user can read a single media asset, and the uploader can
delete it.

**Acceptance scenarios:**
1. **Given** an existing asset, **When** GET `/media/{id}`, **Then** metadata
   including `secureUrl`, `resourceType`, and target reference returns.
2. **Given** Bob tries to delete Alice's media, **Then** the service returns
   `403`.
3. **Given** Alice deletes her media, **Then** the service deletes or
   invalidates the Cloudinary asset and removes or tombstones the metadata.
4. **Given** a missing id, **Then** GET or DELETE returns `404`.

### User Story 5 - Integration demo: auth + post + comment + search + media (Priority: P2)

A Facebook-like host product composes auth, posts, comments, and reusable
media without modifying service code. The demo also indexes the post into
post-search to prove media does not break the existing auth + post + comment
+ search composition.

**Independent test:** `examples/media-standalone/` mounts auth, tweeter,
comment, post-search, and media plug kits. The smoke test creates a post,
creates a comment on that post, indexes the post, uploads media to both
target references, lists media by target, searches the post, and deletes
uploaded assets.

**Acceptance scenarios:**
1. **Given** Cloudinary env vars are present, **When** the demo smoke runs,
   **Then** image/video upload works through Kong and media can attach to both
   `tweeter.post/{postId}` and `comment.comment/{commentId}` while
   post-search still finds the post.
2. **Given** the demo passed, **Then** zero lines of auth-service,
   tweeter-service, comment-service, or media-service source were modified.

### Edge Cases

- Media-service does not validate that the target exists.
- Upload metadata can be stale if a target is later deleted by its owning
  service.
- Cloudinary upload success but database write failure must be handled by
  deleting the uploaded Cloudinary asset or returning a clear failure.
- Database write success but Cloudinary delete failure should be observable in
  logs and preferably retried later.
- Only image/video upload is in scope for v1. Audio, documents, and live
  streaming are out of scope.
- Transformations beyond safe thumbnail URLs are out of scope for v1.

## Requirements

### Functional Requirements

- **FR-001:** All endpoints live under `/media`; Kong applies jwt + rate
  limiting to the whole prefix.
- **FR-002:** `POST /media/targets/{targetType}/{targetId}` accepts
  `multipart/form-data` with a file and optional caption/alt text.
- **FR-003:** The service uploads images/videos to Cloudinary using env-only
  credentials; real credentials must never be committed.
- **FR-004:** The service stores Cloudinary metadata in its own `media-db`:
  targetType, targetId, uploaderUsername, publicId, resourceType, format,
  secureUrl, thumbnailUrl, originalFilename, bytes, width, height, duration,
  createdAt, and deletedAt if soft delete is used.
- **FR-005:** `GET /media/targets/{targetType}/{targetId}` returns target
  media newest-first with cursor paging.
- **FR-006:** `GET /media/{id}` returns one asset's metadata.
- **FR-007:** `DELETE /media/{id}` is uploader-only in v1 and removes the
  Cloudinary asset plus metadata/tombstone.
- **FR-008:** The service validates target keys, allowed media formats, and
  configured size limits before persisting metadata.
- **FR-009:** The service never reads `posts-db`, `comments-db`, or another
  service's tables, and never calls target services for validation.
- **FR-010:** Ships a plug kit under compose profile `media`.
- **FR-011:** Ships `.env.example` with Cloudinary placeholders and a test
  guide explaining when to create the real `.env`.

### Key Entities

- **MediaAsset** - id, targetType, targetId, uploaderUsername, publicId,
  resourceType, format, secureUrl, thumbnailUrl, originalFilename, bytes,
  width, height, durationSeconds, caption, altText, createdAt, deletedAt.

## Success Criteria

- **SC-001:** A user can upload an image and a video through Kong and receive
  Cloudinary-backed URLs.
- **SC-002:** Media can attach to at least two unrelated target namespaces
  without code changes.
- **SC-003:** Target media listing pages correctly and isolates target refs.
- **SC-004:** Owner delete removes uploaded test assets from Cloudinary or
  marks a clear cleanup failure.
- **SC-005:** `examples/media-standalone/smoke.sh` passes with auth + post +
  comment + search + media when real Cloudinary env vars are supplied.
- **SC-006:** Review confirms `.env` is ignored and no real Cloudinary secrets
  are tracked.

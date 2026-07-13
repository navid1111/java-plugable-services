---
name: plugs
description: The pluggable Java backend services available to this app, their real HTTP endpoints, and how to call them through the Kong gateway. Read this before wiring any backend call.
---

# Available backend plugs

All backend calls go through the Kong gateway at `http://localhost:18000`.
In generated JS, define `const GATEWAY = "http://localhost:18000";` and build every fetch() URL from it, e.g. `fetch(GATEWAY + "/posts/feed")`.

**Rules**
- Only call endpoints listed below. Never invent an endpoint or a service.
- Treat the gateway URL as the runtime backend. If a request to it fails, show a visible backend/gateway error instead of silently falling back to mock data.
- If a feature the user asked for has no matching backend, render a visible but disabled placeholder labelled 'Being developed — backend not available yet'. Do not fake it.

## Ready to wire (AVAILABLE)

### Auth Service  (`auth-service`)
- Gateway path(s): /auth
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /auth/me`
  - `GET /auth/me`
  - `GET /internal/users/export`
  - `POST /auth/login`
  - `POST /auth/register`
  - `PUT /auth/profile`

### Bff  (`bff`)
- Gateway path(s): /bff
- Endpoints (call as `GATEWAY + <path>`):
  - `GET /bff/feed`
  - `GET /bff/posts/{id}`

### Booking Service  (`booking-service`)
- Gateway path(s): /bookings
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /bookings/{id}`
  - `GET /bookings/mine`
  - `GET /bookings/resources`
  - `POST /bookings`

### Comment Service  (`comment-service`)
- Gateway path(s): /comments
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /comments/{id}`
  - `GET /comments/targets/{targetType}/{targetId}`
  - `GET /comments/targets/{targetType}/{targetId}/summary`
  - `GET /comments/{id}`
  - `POST /comments/targets/{targetType}/{targetId}`

### Media Service  (`media-service`)
- Gateway path(s): /media
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /media/{id}`
  - `GET /media/targets/{targetType}/{targetId}`
  - `GET /media/targets/{targetType}/{targetId}/summary`
  - `GET /media/{id}`
  - `POST /media/targets/{targetType}/{targetId}`
  - `POST /media/upload-intents`
  - `POST /media/upload-intents/{id}/fail`
  - `POST /media/upload-intents/{id}/finalize`

### Post Search Service  (`post-search-service`)
- Gateway path(s): /post-search
- Endpoints (call as `GATEWAY + <path>`):
  - `GET /internal/projections/posts/shadow-report`
  - `GET /post-search`
  - `GET /post-search/documents/{targetType}/{targetId}`
  - `POST /internal/projections/posts/rebuild`

### Tweeter Service  (`tweeter-service`)
- Gateway path(s): /posts
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /posts/users/{userId}/follow`
  - `DELETE /posts/{id}`
  - `GET /internal/posts/export`
  - `GET /posts`
  - `GET /posts/feed`
  - `GET /posts/{id}`
  - `POST /posts`
  - `PUT /posts/users/{userId}/follow`
  - `PUT /posts/{id}`

### Whatsapp Service  (`whatsapp-service`)
- Gateway path(s): /chat
- Endpoints (call as `GATEWAY + <path>`):
  - `GET /chat/chats`
  - `GET /chat/chats/{id}/messages`
  - `POST /chat/chats`

## Not ready (DEVELOPING — placeholder only)

- `leetcode-service` — no complete plug kit yet; render a disabled placeholder.

## Composition pattern

Content items are **tweeter posts** (`/posts`). Attach media and comments to any item with the shared `targets/{targetType}/{targetId}` pattern:
- A post = `POST /posts`; feed = `GET /posts/feed`; one = `GET /posts/{id}`.
- Media (image/video, Cloudinary-backed) for a post = multipart `POST /media/targets/post/{postId}` (form field `file`); list = `GET /media/targets/post/{postId}`.
- Comments for a post = `POST` / `GET /comments/targets/post/{postId}`.
- Post search requires a query string: `GET /post-search?q=<term>`; do not call
 `/post-search` without `q`.
- Index a post for search with `PUT /post-search/documents/post/{postId}` and JSON
 `{"authorUsername": string, "content": string, "createdAt": ISO-8601 timestamp}`.
  The target type/id are path parameters, not request-body fields.
- Update indexed like counts with `PUT /post-search/documents/post/{postId}/like-count`
 and JSON `{"likeCount": number}`.

Reuse this pattern to compose apps. For example, a YouTube-style app should compose:
- posts/feed for video records and metadata,
- media for upload/playback attachments on each post,
- comments for discussion on each video post, and
- post-search for finding videos when that plug is AVAILABLE.

## Prefer the BFF for composite reads

The **BFF** (`/bff`) composes reads across tweeter + comments + media on the server, with a strict deadline and graceful partial responses. When it is AVAILABLE, use it for read screens instead of fanning out to each service and stitching on the client — one call replaces several and handles slow/failing optional sections for you.

Rule of thumb — **reads through the BFF, writes direct**:
- Feed / post-detail *reads* → call `/bff/*` (below).
- Everything else (create post, follow, comment, upload media, search) → call the owning service directly. The BFF is read-only; it has no write endpoints.

Only these two BFF endpoints exist — never invent others:
- `GET /bff/feed` → `{ items: PostDetail[], nextCursor: string|null, sourceVersionWatermark: number }`
- `GET /bff/posts/{id}` → a single `PostDetail`.

`PostDetail` shape (render defensively — `comments`/`media` may be `null`):
```json
{
  "post":     { "id": number, "content": string, "createdAt": string, "updatedAt": string, "version": number },
  "author":   { "username": string },
  "comments": { "commentCount": number } | null,
  "media":    { "mediaCount": number } | null,
  "degraded": string[]
}
```
- `comments` or `media` is `null` when that optional section could not be composed in time; its name then appears in `degraded`. Show the rest of the post normally
  and treat a listed section as 'temporarily unavailable', not an error.
- A `404` from `/bff/posts/{id}` means the post does not exist (or was deleted) — the BFF only fails the whole read when the owning post itself is missing.

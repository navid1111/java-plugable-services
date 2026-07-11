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
  - `GET /auth/me`
  - `POST /auth/login`
  - `POST /auth/register`

### Comment Service  (`comment-service`)
- Gateway path(s): /comments
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /comments/{id}`
  - `GET /comments/targets/{targetType}/{targetId}`
  - `GET /comments/{id}`
  - `POST /comments/targets/{targetType}/{targetId}`

### Media Service  (`media-service`)
- Gateway path(s): /media
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /media/{id}`
  - `GET /media/targets/{targetType}/{targetId}`
  - `GET /media/{id}`
  - `POST /media/targets/{targetType}/{targetId}`

### Post Search Service  (`post-search-service`)
- Gateway path(s): /post-search
- Endpoints (call as `GATEWAY + <path>`):
  - `GET /post-search`
  - `GET /post-search/documents/{targetType}/{targetId}`
  - `PUT /post-search/documents/{targetType}/{targetId}`
  - `PUT /post-search/documents/{targetType}/{targetId}/like-count`

### Turf Service  (`turf-service`)
- Gateway path(s): /bookings
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /bookings/{id}`
  - `GET /bookings/mine`
  - `GET /bookings/venues`
  - `POST /bookings`

### Tweeter Service  (`tweeter-service`)
- Gateway path(s): /posts
- Endpoints (call as `GATEWAY + <path>`):
  - `DELETE /posts/users/{username}/follow`
  - `GET /posts`
  - `GET /posts/feed`
  - `GET /posts/{id}`
  - `POST /posts`
  - `PUT /posts/users/{username}/follow`

### Whatsapp Service  (`whatsapp-service`)
- Gateway path(s): /chat
- Endpoints (call as `GATEWAY + <path>`):
  - `GET /chat/chats`
  - `GET /chat/chats/{id}/messages`
  - `POST /chat/chats`

### LeetCode Service (`leetcode-service`)
- Gateway path(s): /leetcode
- Endpoints (call as `GATEWAY + <path>`):
  - `GET /leetcode/problems`
  - `GET /leetcode/problems/{id}`
  - `POST /leetcode/problems/{id}/submit`
  - `GET /leetcode/submissions/{id}`
  - `POST /leetcode/competitions`
  - `GET /leetcode/competitions/{id}/leaderboard`

## Not ready (DEVELOPING — placeholder only)


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

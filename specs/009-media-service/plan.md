# Implementation Plan: media-service

**Branch:** `009-media-service` | **Date:** 2026-07-09
**Spec:** [spec.md](spec.md)

## Summary

Build an independent Cloudinary-backed `media-service` for images and videos.
The API is target-reference based, like comments: media attaches to
`targetType + targetId` rather than to a hard-coded post or comment table.
The service owns media metadata and Cloudinary provider interactions, but it
does not own target resources.

## Technical Context

- **Language/runtime:** Java 21, Spring Boot 4.1, same scaffold pattern as
  existing services
- **Storage:** PostgreSQL `media-db`; stores metadata only, never binary media
- **Provider:** Cloudinary Java SDK or signed Cloudinary HTTP API, selected
  during implementation after checking the current official docs
- **Upload style v1:** backend proxy upload via `multipart/form-data`
- **Identity:** JWT-decode helper copied from existing services; Kong verifies
  jwt first
- **Gateway:** `/media` route + `jwt` plugin + rate limiting via plug kit
- **Compose profile:** `media`
- **Configuration:** `.env.example` committed; real `.env` created only before
  Docker/smoke testing and never committed
- **Integration proof:** `examples/media-standalone/` composes auth + tweeter
  + comment + post-search + media

## Constitution Check

| Article | Status |
|---------|--------|
| I - one DB per service | Pass: `media-db` only |
| II - auth at the edge | Pass: Kong jwt on `/media`; service reads `sub` |
| III - identity by reference | Pass: stores usernames and target refs only |
| IV - plug kit | Pass when `media-service/plug/` exists |
| V - no service-to-service calls | Pass: no synchronous post/comment lookup |
| VI - single ownership | Pass: media owns metadata/provider cleanup only |
| VII - integration demo | Pass when `examples/media-standalone/` is green |
| VIII - right-sized | Pass: Cloudinary + Postgres metadata; no event bus yet |

## Design Decisions

1. **Generic target references.** The service uses
   `/media/targets/{targetType}/{targetId}` so media can attach to posts,
   comments, videos, venues, or future products without schema changes.
2. **Cloudinary-backed, not Cloudinary-shaped.** The public API is `/media`;
   Cloudinary is an implementation detail behind it. This keeps the product
   boundary reusable if storage changes later.
3. **Metadata database only.** Binary media lives in Cloudinary. The service
   stores URLs, public ids, dimensions, duration, size, and target refs.
4. **Backend upload in v1.** The service receives multipart uploads and sends
   them to Cloudinary. This is simplest to test through Kong. A future direct
   browser upload flow can add signed upload parameters.
5. **Uploader-only delete.** V1 lets the uploader delete their own assets.
   Target-owner moderation can be added later when the platform has richer
   roles or resource ownership lookup.
6. **Explicit cleanup behavior.** If DB persistence fails after Cloudinary
   upload, implementation should attempt to destroy the provider asset before
   returning an error.
7. **Real credentials only at test time.** The implementation and docs must
   never require real Cloudinary credentials in tracked files. `.env.example`
   is the template; `.env` is local-only.

## API Sketch

```text
POST   /media/targets/{targetType}/{targetId}
GET    /media/{id}
GET    /media/targets/{targetType}/{targetId}?cursor=&pageSize=
DELETE /media/{id}
```

Upload body:

```text
multipart/form-data
file=<image-or-video>
caption=<optional>
altText=<optional>
```

Response body:

```json
{
  "id": 1,
  "targetType": "tweeter.post",
  "targetId": "123",
  "uploaderUsername": "alice",
  "resourceType": "image",
  "format": "png",
  "secureUrl": "https://res.cloudinary.com/...",
  "thumbnailUrl": "https://res.cloudinary.com/...",
  "bytes": 12345,
  "width": 800,
  "height": 600,
  "durationSeconds": null,
  "createdAt": "2026-07-09T00:00:00Z"
}
```

## Data Model

`MediaAsset`

- `id`
- `targetType`
- `targetId`
- `uploaderUsername`
- `publicId`
- `resourceType`
- `format`
- `secureUrl`
- `thumbnailUrl`
- `originalFilename`
- `bytes`
- `width`
- `height`
- `durationSeconds`
- `caption`
- `altText`
- `createdAt`
- `deletedAt`

Indexes:

- `(target_type, target_id, created_at DESC, id DESC)` for target paging
- `(uploader_username, created_at DESC, id DESC)` for user cleanup tools
- unique `public_id` for provider cleanup safety

## Risks

- **Real provider credentials:** Tests cannot fully pass until the developer
  creates a local `.env` with real Cloudinary values.
- **Provider cost/quota:** Smoke tests should upload tiny files and delete
  them afterward.
- **Large multipart uploads through Kong:** V1 should set conservative size
  limits. Later direct-to-Cloudinary upload can reduce gateway/backend load.
- **Dual-write cleanup:** Cloudinary upload and DB insert are not one
  transaction. Failed DB insert after upload needs best-effort provider
  cleanup.
- **Target lifecycle:** If a post/comment is deleted, media remains unless
  the composed app calls delete or a future event flow cleans it up.

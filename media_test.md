# Testing Guide and Terminal Commands

This document describes the planned tests for the Cloudinary-backed
`media-service`.

## When to Add the Real `.env`

Do **not** add a real `.env` during the spec/plan phase.

Add the real `.env` only after the media-service implementation exists and
right before you run Docker Compose or Cloudinary smoke tests.

Use this sequence:

```bash
cp .env.example .env
```

Then edit `.env` and fill these real Cloudinary values from your Cloudinary
dashboard:

```bash
CLOUDINARY_CLOUD_NAME=<your cloud name>
CLOUDINARY_API_KEY=<your api key>
CLOUDINARY_API_SECRET=<your api secret>
```

Keep `.env` local. It is ignored by git. Only `.env.example` should be
committed.

## 1. Environment Checklist

Before running media tests, confirm:

- `.env` exists locally
- `JWT_SECRET` and `JWT_ISSUER` are set
- Cloudinary cloud name, API key, and API secret are set
- `CLOUDINARY_UPLOAD_FOLDER` points to a safe dev folder
- test files are tiny and safe to delete

Recommended Cloudinary folder:

```bash
CLOUDINARY_UPLOAD_FOLDER=pluggable-services-dev
```

## 2. Root Stack Commands

**Start auth + media:**

```bash
docker compose --profile media up --build -d
```

**Configure Kong:**

```bash
./kong/setup-core.sh
./kong/setup-media.sh
```

**Check health:**

```bash
docker compose --profile media ps
```

Expected healthy services:

- `kong`
- `kong-database`
- `users-db`
- `auth-service`
- `media-db`
- `media-service`

**Tear down:**

```bash
docker compose --profile media down -v
```

## 3. Service-Level Smoke Test

Run the media plug smoke after `.env` is ready:

```bash
./media-service/plug/smoke.sh
```

Planned smoke coverage:

- register/login through `/auth`
- `401` for missing token on `/media`
- upload one generated tiny image to `tweeter.post/post_<stamp>`
- optionally upload one local video when `MEDIA_SMOKE_VIDEO_PATH` is set
- list media by target
- verify target isolation
- delete uploaded assets so Cloudinary is cleaned up

Optional video test:

```bash
MEDIA_SMOKE_VIDEO_PATH=/tmp/small-test-video.mp4 ./media-service/plug/smoke.sh
```

## 4. Manual API Test Plan

The API is exposed through Kong at `http://localhost:18000`.

### A. Register and Login

```bash
curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'

TOKEN=$(curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

printf 'token length: %s\n' "${#TOKEN}"
```

### B. Confirm Missing Token is Rejected

```bash
curl -i -s http://localhost:18000/media/targets/tweeter.post/post_123
```

Expected output: `401 Unauthorized`.

### C. Create a Tiny Image Fixture

```bash
printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=' \
  | base64 -d > /tmp/media-smoke.png
```

### D. Upload Image to a Post Target

```bash
curl -i -s -X POST http://localhost:18000/media/targets/tweeter.post/post_123 \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/media-smoke.png;type=image/png" \
  -F "caption=small image smoke" \
  -F "altText=one pixel test image"
```

Expected output: `201 Created` with:

- media id
- targetType and targetId
- uploaderUsername
- resourceType `image`
- Cloudinary publicId
- secureUrl
- thumbnailUrl
- dimensions and byte size

### E. Upload Video to a Comment Target

Use a small local video file:

```bash
curl -i -s -X POST http://localhost:18000/media/targets/comment.comment/comment_456 \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/small-test-video.mp4;type=video/mp4" \
  -F "caption=small video smoke"
```

Expected output: `201 Created` with resourceType `video`.

### F. List Media by Target

```bash
curl -i -s "http://localhost:18000/media/targets/tweeter.post/post_123?pageSize=2" \
  -H "Authorization: Bearer $TOKEN"
```

Expected output:

- `200 OK`
- newest-first media for only `tweeter.post/post_123`
- `nextCursor` if more rows exist

### G. Read One Asset

```bash
curl -i -s http://localhost:18000/media/1 \
  -H "Authorization: Bearer $TOKEN"
```

Expected output: `200 OK` if media `1` exists, otherwise `404`.

### H. Delete Own Asset

```bash
curl -i -s -X DELETE http://localhost:18000/media/1 \
  -H "Authorization: Bearer $TOKEN"
```

Expected output: `204 No Content` when the requester uploaded the asset.

The service should also clean up the Cloudinary asset. If provider cleanup
fails, the response or logs should make that failure visible.

## 5. Final Integration Demo Plan

The final demo will live in:

```text
examples/media-standalone/
```

It will prove:

1. Auth issues tokens.
2. Tweeter creates a real post.
3. Comment-service comments on that post.
4. Post-search indexes and finds the post.
5. Media-service uploads image/video assets to:
   - `tweeter.post/{postId}`
   - `comment.comment/{commentId}`
6. Media listing works for both target namespaces.
7. Uploaded test assets are deleted from Cloudinary at the end.

## 6. Local Java Verification

After implementation, compile without Docker:

```bash
mvn -q -Dmaven.repo.local=/home/navid/java/.m2-tmp -DskipTests package
```

Run this from inside `media-service/`.

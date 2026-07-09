# Plug Services One by One

This guide shows the intended growth path for the pluggable service system:

1. `auth-service`
2. `tweeter-service`
3. `comment-service`
4. `post-search-service`

Each service owns its own database and exposes a `plug/` kit:

- `compose.plug.yml` — containers needed by that service
- `kong-setup.sh` — idempotent Kong route/plugin registration
- `smoke.sh` — gateway-level verification script

Kong is the stable front door. Services should not casually call each other. The host app composes them by using their public HTTP APIs and stable target keys like `tweeter.post/{postId}`.

## Base mental model

```text
Client / host app
  |
  v
Kong :18000
  |
  +-- /auth/*         -> auth-service
  +-- /posts/*        -> tweeter-service
  +-- /comments/*     -> comment-service
  +-- /post-search/*  -> post-search-service
```

Service ownership:

```text
auth-service        owns users + JWT issuing
tweeter-service     owns posts/follows/feed
comment-service     owns comments attached to generic target keys
post-search-service owns searchable post snapshots + inverted index
```

The long-term production evolution is event-driven, but the current v1 plug path is synchronous and explicit: the host app creates a post, then pushes the relevant target key or snapshot to the next service.

---

# 0. Prerequisites

From the repository root:

```bash
cd /home/navid/java
```

Create shared JWT config for local composition:

```bash
cat > .env <<'EOF'
JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789
JWT_ISSUER=springboot-auth
EOF
```

Start with a clean stack when needed:

```bash
docker compose down -v
```

Useful Kong checks:

```bash
curl http://localhost:8001/services
curl http://localhost:8001/routes
curl http://localhost:8001/plugins
curl http://localhost:8001/consumers
```

---

# 1. Plug auth-service first

## Purpose

`auth-service` is the identity foundation.

It provides:

- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/me`
- JWT issuing
- user database ownership

Other services do not authenticate passwords. They trust Kong JWT verification and decode the JWT subject as the username.

## Start auth only

```bash
docker compose --profile auth up --build -d
```

## Configure Kong

```bash
./kong/setup-core.sh
```

This registers the auth route and configures the shared JWT issuer/consumer expected by protected services.

## Verify

```bash
./auth-service/plug/smoke.sh
```

Expected result:

- user can register
- user can login
- token can call `/auth/me`

At this point the platform has identity, but no product features.

---

# 2. Plug tweeter-service second

## Purpose

`tweeter-service` owns the social/post domain.

It provides:

- `POST /posts`
- follow/unfollow APIs
- feed APIs
- its own posts/follows database

It depends on auth only through JWT. It does not read the auth database.

## Start auth + tweeter

```bash
docker compose --profile tweeter up --build -d
```

## Configure Kong

```bash
./kong/setup-core.sh
./kong/setup-tweeter.sh
```

## Verify

```bash
./tweeter-service/plug/smoke.sh
```

Expected result:

- users can register/login through auth
- authenticated users can create posts
- follow/feed flows work through Kong

## Important integration contract

When a post is created, `tweeter-service` returns a post response with fields like:

```json
{
  "id": 123,
  "authorUsername": "alice",
  "content": "hello world",
  "createdAt": "2026-07-10T00:00:00Z"
}
```

That post remains owned by tweeter. Other services should refer to it using this generic target key:

```text
tweeter.post/123
```

At this point the platform has users and posts.

---

# 3. Plug comment-service third

## Purpose

`comment-service` owns comments, but not posts.

It attaches comments to generic targets:

```text
{targetType}/{targetId}
```

Examples:

```text
tweeter.post/123
youtube.video/abc123
blog.article/my-post
```

This keeps comments reusable. The service does not call `tweeter-service` to check if a post exists in v1.

## Start auth + tweeter + comments

```bash
docker compose --profile comments up --build -d
```

Depending on your compose profiles, if you want tweeter running too, use the profile combination that starts both tweeter and comments. The root README currently exposes profiles individually; confirm with:

```bash
docker compose config --profiles
```

## Configure Kong

```bash
./kong/setup-core.sh
./kong/setup-tweeter.sh
./kong/setup-comments.sh
```

## Verify comment-service alone

```bash
./comment-service/plug/smoke.sh
```

This proves comments can attach to generic target keys even without a live tweeter dependency.

## Verify composed post comments

Use the post-search standalone flow later for the full auth + post + comment path, or manually:

```bash
# 1. register/login with auth
# 2. create a post with POST /posts
# 3. take returned id, for example 123
# 4. create a comment:
curl -X POST http://localhost:18000/comments/targets/tweeter.post/123 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"nice post"}'

# 5. read comments for the post target:
curl "http://localhost:18000/comments/targets/tweeter.post/123" \
  -H "Authorization: Bearer $TOKEN"
```

## Important integration contract

The host app is responsible for passing a real target key:

```text
tweeter.post/{postId}
```

`comment-service` stores:

```text
target_type = tweeter.post
target_id   = {postId}
author      = JWT subject
content     = request body content
```

At this point the platform has users, posts, and comments.

---

# 4. Plug post-search-service fourth

## Purpose

`post-search-service` owns search indexing and search reads.

It does not fetch posts from `tweeter-service`.

Instead, the host app pushes searchable snapshots into search after creating/updating posts.

## Start auth + tweeter + comments + post-search

The clearest existing full demo is:

```bash
cd /home/navid/java/examples/post-search-standalone
```

Build images from repo root first if needed:

```bash
cd /home/navid/java
docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
docker build -t tweeter-service:latest -f tweeter-service/Dockerfile tweeter-service/
docker build -t comment-service:latest -f comment-service/Dockerfile comment-service/
docker build -t post-search-service:latest -f post-search-service/Dockerfile post-search-service/
```

Then run the standalone composition:

```bash
cd /home/navid/java/examples/post-search-standalone
cat > .env <<'EOF'
JWT_SECRET=super-secret-jwt-key-for-post-search-demo-32-bytes
JWT_ISSUER=springboot-auth
EOF

docker compose up -d
```

## Configure Kong

```bash
../../auth-service/plug/kong-setup.sh

JWT_SECRET="$(grep '^JWT_SECRET=' .env | cut -d= -f2-)"
JWT_ISSUER="$(grep '^JWT_ISSUER=' .env | cut -d= -f2-)"

curl -fsS -X PUT http://localhost:8001/consumers/springboot-auth
curl -fsS -X POST http://localhost:8001/consumers/springboot-auth/jwt \
  --data "algorithm=HS256" \
  --data "key=${JWT_ISSUER}" \
  --data "secret=${JWT_SECRET}" \
  || curl -fsS -X PATCH "http://localhost:8001/consumers/springboot-auth/jwt/${JWT_ISSUER}" \
    --data "algorithm=HS256" \
    --data "key=${JWT_ISSUER}" \
    --data "secret=${JWT_SECRET}"

../../tweeter-service/plug/kong-setup.sh
../../comment-service/plug/kong-setup.sh
../../post-search-service/plug/kong-setup.sh
```

## Verify full flow

```bash
./smoke.sh
```

Expected result:

- auth registers/logs in users
- tweeter creates real posts
- comment-service comments on a post target
- post-search indexes post snapshots
- search finds posts by keyword
- search can rank by recency or likes

## Important integration contract

After creating a post, the host app indexes the returned snapshot:

```bash
curl -X PUT "http://localhost:18000/post-search/documents/tweeter.post/${POST_ID}" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"authorUsername\":\"${AUTHOR}\",\"content\":\"${CONTENT}\",\"createdAt\":\"${CREATED_AT}\"}"
```

For ranking updates:

```bash
curl -X PUT "http://localhost:18000/post-search/documents/tweeter.post/${POST_ID}/like-count" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"likeCount":42}'
```

Search:

```bash
curl "http://localhost:18000/post-search?q=java%20spring&sort=recency&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

At this point the platform has users, posts, comments, and searchable post snapshots.

---

# Final composed request flow

```text
1. Register/login
   Client -> Kong -> auth-service

2. Create post
   Client -> Kong -> tweeter-service
   returns post id/content/createdAt

3. Comment on post
   Client -> Kong -> comment-service
   target = tweeter.post/{postId}

4. Index post for search
   Client/host app -> Kong -> post-search-service
   target = tweeter.post/{postId}
   snapshot = author/content/createdAt

5. Search posts
   Client -> Kong -> post-search-service
```

---

# Current v1 vs long-term event-driven version

## Current v1

```text
Host app orchestrates writes explicitly.
```

Pros:

- simple
- easy to demo
- services stay independently pluggable
- no broker required

Cons:

- caller must remember to index post snapshots
- caller must update search like counts
- comment-service cannot verify target existence locally
- partial failure can create stale projections

## Future v2

Introduce a message queue/event bus plus transactional outbox.

```text
tweeter-service DB transaction
  -> outbox_events row
  -> publisher
  -> RabbitMQ/Kafka
  -> post-search/comment/media consumers
  -> local projections
```

Useful events:

- `PostCreated`
- `PostUpdated`
- `PostDeleted`
- `PostLikeCountChanged`
- `CommentCreated`
- `CommentDeleted`
- `MediaUploaded`
- `MediaDeleted`

Then:

- search indexes posts from events
- comments can validate against a local known-target table
- media can clean up on target deletion
- services stay decoupled without pushing all orchestration into the frontend

Recommended first broker for this repo: RabbitMQ. It is easier to run locally with Docker Compose and easy to explain in interviews.

---

# Quick command checklist

From `/home/navid/java`:

```bash
# Auth
./kong/setup-core.sh
./auth-service/plug/smoke.sh

# Tweeter
./kong/setup-tweeter.sh
./tweeter-service/plug/smoke.sh

# Comments
./kong/setup-comments.sh
./comment-service/plug/smoke.sh

# Search
./kong/setup-post-search.sh
./post-search-service/plug/smoke.sh
```

For the strongest full-composition demo, use:

```bash
cd /home/navid/java/examples/post-search-standalone
docker compose up -d
./smoke.sh
```

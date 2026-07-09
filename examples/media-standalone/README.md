# Media Service Standalone Integration Demo

This demo composes `auth-service`, `tweeter-service`, `comment-service`,
`post-search-service`, and `media-service` behind a fresh Kong. It proves
media can attach to both posts and comments using target references, while
post-search still finds the post by keyword.

## Before Running: Create `.env`

Create the real `.env` only before this test, never during planning and never
as a committed file:

```bash
cp .env.example .env
```

Fill these Cloudinary values in the root `.env`:

```bash
CLOUDINARY_CLOUD_NAME=<your cloud name>
CLOUDINARY_API_KEY=<your api key>
CLOUDINARY_API_SECRET=<your api secret>
```

The root `.env` is ignored by git.

## How to run

1. Build the images from the root of the repo:

   ```bash
   docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
   docker build -t tweeter-service:latest -f tweeter-service/Dockerfile tweeter-service/
   docker build -t comment-service:latest -f comment-service/Dockerfile comment-service/
   docker build -t post-search-service:latest -f post-search-service/Dockerfile post-search-service/
   docker build -t media-service:latest -f media-service/Dockerfile media-service/
   ```

2. Start the stack from this directory, reading the root `.env`:

   ```bash
   cd examples/media-standalone/
   docker compose --env-file ../../.env up -d
   ```

3. Register routes and JWT credential with Kong:

   ```bash
   cd ../../
   ./kong/setup-core.sh
   ./kong/setup-tweeter.sh
   ./kong/setup-comments.sh
   ./kong/setup-post-search.sh
   ./kong/setup-media.sh
   cd examples/media-standalone/
   ```

4. Run the full integration smoke:

   ```bash
   ./smoke.sh
   ```

The smoke test creates a post, comments on that post, indexes the post for
search, uploads media to `tweeter.post/{postId}`, uploads media to
`comment.comment/{commentId}`, lists media for both targets, searches the
post, and deletes uploaded Cloudinary assets.

## Tear down

```bash
docker compose --env-file ../../.env down -v
```

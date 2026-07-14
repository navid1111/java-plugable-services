# Pluggable Services Platform

A microservices platform where **Kong** sits at the edge and verifies **JWTs**, and every
service is packaged as a self-contained **plug kit** that can be snapped onto the gateway
independently. Each service owns its own **Postgres** database; data crosses service
boundaries only through **RabbitMQ events** or **workload-JWT HTTP export** — never shared
tables. Everything runs in Docker on a single `kong-net` bridge network.

The `auth-service` mints tokens (login against its own users table); Kong verifies them for
every other route. A **BFF** composes client-shaped reads, and an **App Builder** can be
exposed publicly to generate apps against the live gateway.

> 📐 Full diagrams, domain models, and design rationale per service live in
> [`final architecture/`](final%20architecture/). This README is the operational entry point.

## System topology

```mermaid
graph TB
    Client([Client / Frontend])

    subgraph Edge["🌐 Edge"]
        Kong["Kong API Gateway<br/>:18000 proxy · :18001 admin<br/>JWT verify + rate limiting"]
        KongDB[("kong-database<br/>Postgres — gateway config")]
        Kong -.config.-> KongDB
    end

    Client -->|HTTPS + Bearer JWT| Kong

    subgraph Services["⚙️ Owning Services (Spring Boot, each own DB)"]
        Auth["auth-service · /auth<br/>issues JWT (public)"]
        Tweeter["tweeter-service · /posts<br/>posts, follows, feed"]
        Comment["comment-service · /comments<br/>generic target comments"]
        Search["post-search-service · /post-search<br/>inverted index (read model)"]
        Media["media-service · /media<br/>Cloudinary attachments"]
        Booking["booking-service · /bookings<br/>slot booking (standalone)"]
        Whatsapp["whatsapp-service · /chat<br/>REST + WebSocket (standalone)"]
        Leet["leetcode-service · /leetcode<br/>problems + submissions"]
        LeetWorker["leetcode-judge-worker<br/>sandboxed code execution"]
        BFF["bff · /bff<br/>read composer (no DB)"]
    end

    Kong -->|/auth PUBLIC| Auth
    Kong -->|/posts JWT| Tweeter
    Kong -->|/comments JWT| Comment
    Kong -->|/post-search JWT| Search
    Kong -->|/media JWT| Media
    Kong -->|/bookings JWT| Booking
    Kong -->|/chat JWT| Whatsapp
    Kong -->|/leetcode JWT| Leet
    Kong -->|/bff JWT| BFF

    subgraph Platform["🔌 Platform (shared infra)"]
        MQ{{"RabbitMQ<br/>topic exchange: platform.events.v1<br/>+ DLX: platform.events.dlx"}}
        Prom["Prometheus :9090"]
        Graf["Grafana :3000"]
    end

    Auth -. "user.*" .-> MQ
    Tweeter -. "post.* / follow.*" .-> MQ
    Comment -. "comment.*" .-> MQ
    Media -. "media.*" .-> MQ
    MQ -. "post.deleted" .-> Comment
    MQ -. "post.updated / post.deleted" .-> Search
    MQ -. "post.deleted" .-> Media
    Leet -. "judge.requested" .-> MQ
    MQ -. "judge.requested" .-> LeetWorker
    LeetWorker -. "judge.completed" .-> MQ
    MQ -. "judge.completed" .-> Leet

    Comment ==>|export backfill (workload JWT)| Tweeter
    Search ==>|export backfill| Tweeter
    Media ==>|export backfill| Tweeter
    BFF ==>|read-compose| Tweeter
    BFF ==> Comment
    BFF ==> Media

    Media -->|upload| Cloud([Cloudinary CDN])
    Services -.metrics.-> Prom
    Prom --> Graf
```

**Legend:** solid `-->` = sync HTTP through Kong · double `==>` = internal sync HTTP
(workload-JWT) · dotted `-.->` = async event over RabbitMQ.

## Services

| Service | Prefix | Profile | Responsibility |
|---------|--------|---------|----------------|
| [auth-service](final%20architecture/01-auth-service.md) | `/auth` | `auth` | User identity + **JWT minting**; routes are public |
| [tweeter-service](final%20architecture/02-tweeter-service.md) | `/posts` | `tweeter` | Posts, follows, cursor feed; **export origin**; outbox |
| [comment-service](final%20architecture/03-comment-service.md) | `/comments` | `comments` | Reusable comments on any `{targetType}/{targetId}` |
| [post-search-service](final%20architecture/04-post-search-service.md) | `/post-search` | `post-search` | Keyword search over a manual inverted index (read model) |
| [media-service](final%20architecture/05-media-service.md) | `/media` | `media` | Cloudinary attachments; upload-intent + retry/DLQ deletion |
| [booking-service](final%20architecture/06-booking-service.md) | `/bookings` | `booking` | Generic slot booking; DB-enforced no-double-booking |
| [whatsapp-service](final%20architecture/07-whatsapp-service.md) | `/chat` | `chat` | Chat: REST history + WebSocket delivery + inbox |
| [leetcode-service](final%20architecture/08-leetcode-service.md) | `/leetcode` | `leetcode` | Problems + submissions + **sandboxed judge worker** (2 roles) |
| [bff](final%20architecture/09-bff.md) | `/bff` | `bff` | Client-shaped read composer; critical vs optional, deadlines |
| [platform](final%20architecture/10-platform-messaging-observability.md) | — | `observability` | RabbitMQ backbone, event contracts, outbox, metrics |

All services listen on `:8080` **inside** the Docker network and are reachable only through
Kong at host port **`:18000`**.

## Cross-cutting patterns

- **Edge auth** — Kong verifies the JWT at the gateway; services trust the verified token.
  The signing secret/issuer (`JWT_SECRET` / `JWT_ISSUER`) must match Kong's JWT credential.
- **Database-per-service** — no shared tables; data crosses boundaries via events or
  workload-JWT HTTP export.
- **Transactional outbox** — domain write + event row committed in one transaction; a
  scheduled relay publishes to RabbitMQ and marks sent (at-least-once, no dual-write race).
- **Workload JWT** — a separate identity plane for internal `/internal/*` service-to-service
  calls, distinct from end-user JWTs (trusted-caller list, small clock skew).
- **Denormalized identity** — services store `userId` (UUID) + username so reads never call
  auth synchronously; `IdentityBackfill` reconciles gaps from auth/tweeter exports.
- **Plug-kit packaging** — each service ships a `plug/` dir (compose fragment,
  `kong-setup.sh`, `smoke.sh`) so it can be plugged into the gateway independently.

## Event catalog

One durable topic exchange `platform.events.v1` (routing key = event type); each consumer
binds only the keys it needs, with a dead-letter path via `platform.events.dlx` → `<consumer>.dlq`.

| Domain | Events | Produced by | Consumed by |
|--------|--------|-------------|-------------|
| user | `user.registered/profile-updated/deactivated.v1` | auth | projections / backfill |
| post | `post.created/updated/deleted.v1`, `post.like-count-changed.v1` | tweeter | post-search, comment, media |
| follow | `follow.created/deleted.v1` | tweeter | — |
| comment | `comment.created/deleted.v1` | comment | — |
| media | `media.uploaded/processing-completed/processing-failed/deleted.v1` | media | — |
| leetcode | `leetcode.submission.judge.requested/completed.v1` | leetcode api / worker | worker / api |

Events are wrapped in a shared versioned `EventEnvelope` (`.v1`), guarded by contract tests.

## Prerequisites

- Docker + Docker Compose
- No local Java/Maven needed for the services — each is built inside its Docker image.
- A `.env` file (see below). For media, set your Cloudinary credentials; for the App Builder
  deploy, set `NGROK_AUTH_TOKEN`.

## Run

Services are turned on à la carte with **compose profiles**. `rabbitmq` joins automatically
for any event-driven profile; `users-db` + `auth-service` come up for every app profile
(shared identity).

```bash
# 1. Create a .env file with your JWT secrets
echo "JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789" > .env
echo "JWT_ISSUER=springboot-auth" >> .env

# 2. Start the stack for the profiles you want (repeatable / additive)
docker compose --profile auth        up --build -d   # identity only
docker compose --profile tweeter     up --build -d   # posts + follows + feed
docker compose --profile comments    up --build -d   # reusable comments
docker compose --profile post-search up --build -d   # keyword search
docker compose --profile media       up --build -d   # Cloudinary attachments
docker compose --profile booking     up --build -d   # slot booking
docker compose --profile chat        up --build -d   # whatsapp-style chat
docker compose --profile leetcode    up --build -d   # problems + judge worker
docker compose --profile bff         up --build -d   # read composer
docker compose --profile observability up -d         # Prometheus + Grafana

# 3. Once Kong is healthy, register each enabled service at the gateway
./kong/setup-core.sh          # jwt issuer + auth-service (always first)
./kong/setup-tweeter.sh       # /posts
./kong/setup-comments.sh      # /comments
./kong/setup-post-search.sh   # /post-search
./kong/setup-media.sh         # /media
./kong/setup-booking.sh       # /bookings
./kong/setup-chat.sh          # /chat
./kong/setup-leetcode.sh      # /leetcode
./kong/setup-bff.sh           # /bff
```

Run only the `setup-*.sh` scripts for the profiles you actually started.

## Deploy (App Builder, public via ngrok)

The **App Builder** UI can be exposed publicly with one script:

```bash
./scripts/deploy.sh
```

It brings up everything the App Builder needs, in order, and opens an ngrok tunnel:

1. Docker backend stack → Kong gateway on `:18000`
2. Catalog service (`app-builder` Spring Boot) on `:8080` — **required**, or workspace
   creation fails with _"Could not create an app workspace"_
3. App Builder UI (FastAPI) on `:8090`
4. ngrok → public URL forwarding to `:8090`

Requires `NGROK_AUTH_TOKEN` in `.env`. Set `NGROK_URL` (or `NGROK_DOMAIN`) to pin a reserved
domain; otherwise ngrok assigns one. Logs are written to `.deploy-logs/`. Generated apps call
the gateway at `localhost:18000` at runtime, so remote visitors' generated apps need the
gateway reachable too.

> ⚠️ **Free-tier ngrok allows only one tunnel at a time.** If another ngrok agent is already
> running (e.g. a different project sharing the same authtoken/reserved domain), `deploy.sh`
> detects it and leaves the App Builder reachable **locally at http://localhost:8090** instead
> of starting a second tunnel. Stop the other agent first to make this app public.

## Test it

Each service ships a smoke test that exercises its happy path **through the gateway**. Run the
ones whose profiles are up:

```bash
./auth-service/plug/smoke.sh          # register → login → /auth/me
./tweeter-service/plug/smoke.sh
./comment-service/plug/smoke.sh
./post-search-service/plug/smoke.sh
./media-service/plug/smoke.sh
./booking-service/plug/smoke.sh
./whatsapp-service/plug/smoke.sh
./leetcode-service/plug/smoke.sh
./bff/plug/smoke.sh
```

## Useful admin calls

Kong's admin API is on host port **`18001`** (container `8001`):

```bash
curl http://localhost:18001/services     # registered services
curl http://localhost:18001/routes       # routes
curl http://localhost:18001/plugins      # enabled plugins
curl http://localhost:18001/consumers    # API consumers
```

## Observability

Enable the `observability` profile to scrape metrics and view dashboards:

- **Prometheus** (`:9090`) scrapes each service's `/actuator/prometheus`; alert rules under
  `platform/observability/prometheus/rules`.
- **Grafana** (`:3000`) with provisioned dashboards + datasource. DLQ depth
  (`messaging.dlq.count`) and standard app/JVM metrics feed the dashboards.

## Tear down

```bash
docker compose down          # stop containers
docker compose down -v       # stop AND wipe Postgres volumes
```

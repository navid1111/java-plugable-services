# Facebook News Feed — Architecture Breakdown

> Source: [Hello Interview — FB News Feed](https://www.hellointerview.com/learn/system-design/problem-breakdowns/fb-news-feed)
> Maps to: **tweeter-service** (`/posts`) in this repo — posts, follow graph, reverse-chron feed.

## 1. Requirements

### Functional (core)
1. Create/publish posts
2. Follow/unfollow other users (uni-directional)
3. View a reverse-chronological feed of followed accounts
4. Page through the feed with cursor-based pagination

Out of scope: likes, comments, privacy controls, bi-directional friendships.

### Non-functional (core)
1. **Availability over consistency** — up to ~1 minute of post staleness is fine (eventual consistency)
2. Feed views and post creation complete within 500ms
3. Scale: 2 billion users, unlimited follows

## 2. Core Entities

- **User** — system participant
- **Follow** — uni-directional (follower → followee) relationship
- **Post** — user-generated content visible to followers

## 3. API

```
POST /posts                {content}            → {postId}
PUT  /users/[id]/follow                         → 200 OK
GET  /feed?pageSize=&cursor={timestamp}         → {items: Post[], nextCursor}
```

The cursor is a timestamp; each page returns increasingly older posts. Cursor pagination (not offset) is deliberate — it stays stable under eventual consistency and concurrent inserts.

## 4. High-Level Architecture

```
Clients
  ↓
Load Balancer → API Gateway
  ↓
┌──────────────┬────────────────┬──────────────┐
Post Service   Follow Service   Feed Service
└──────┬───────┴───────┬────────┴──────┬───────┘
       ↓               ↓               ↓
   DynamoDB        DynamoDB       (orchestrates
   Posts table     Follow table    reads of both)
   PK: creatorId   PK: userFollowing
   SK: createdAt   SK: userFollowed
   + GSI            + GSI reversed
```

- **Post Service** — stateless; posts partitioned by creator ID, sorted by creation time, GSI for lookup-by-creator.
- **Follow Service** — Follow table indexed both directions: "who do I follow" (primary) and "who follows me" (GSI).
- **Feed Service** — naive flow: fetch followees → fetch their recent posts via the Post GSI → merge-sort by timestamp → apply cursor.

## 5. Deep Dives

### 5.1 Fan-out on read hurts users who follow thousands of accounts

**Problem:** building the feed at read time means N queries for N followees — massive read amplification.

**Solution — precomputed feed table:**
- Store each user's ~200 most recent feed posts in a dedicated table keyed by userId → one lookup serves the feed.
- Updated **asynchronously** when someone they follow posts.
- Storage: ~2 KB per user ≈ 4 TB for 2B users — acceptable.
- Users who scroll past the precomputed window fall back to naive queries.

### 5.2 Fan-out on write hurts celebrities (the celebrity problem)

**Problem:** one post by an account with 90M followers = 90M feed-table writes.

| Approach | Idea | Verdict |
|----------|------|---------|
| Synchronous blast | Write all follower feeds inline | ❌ Connection limits, uneven load |
| Async workers | Post creation enqueues to SQS; worker fleet prepends the post to follower feeds; ≤1 min lag is within the staleness budget | ✅ Good |
| **Hybrid feeds** | Flag high-follower accounts; **skip precomputation for them**; at read time merge the precomputed feed with fresh queries for celebrity followees | ✅ **Great** |

The hybrid adapts per account: normal users get fan-out-on-write, celebrities get fan-out-on-read, and every reader merges the two.

### 5.3 Hot key problem (viral posts)

**Problem:** millions reading one post ID hammers a single DynamoDB partition.

- *Good:* Redis cache of posts by ID (long TTL, LRU) — the cache shard holding the hot key still gets hammered.
- *Great:* **Replicated cache** — N Redis instances each holding the full hot-post set, load balancer spreads reads across them. Costs up to N cache-miss DB reads at warm-up, but throughput scales with N.

## 6. Storage choices

| Use case | Tech | Why |
|----------|------|-----|
| Posts, Follows, Feeds | DynamoDB | High throughput with even key distribution; GSIs cover both access directions |
| Hot post cache | Redis (replicated) | Absorbs viral-read load |
| Async fan-out | SQS + workers | Buffers write amplification, tolerated by the 1-min staleness budget |

## 7. Key design principles

1. Prefer **write-time computation** (precompute feeds) over read-time work — but adapt per account.
2. **Cursor-based pagination** over offset — stable under eventual consistency.
3. **Replication over sharding** for hot data — sharding can't split a single hot key, replication can.
4. **Queue-driven async processing** wherever the latency budget allows.

## 8. Mapping to tweeter-service (this repo)

| Interview design | tweeter-service (right-sized) |
|------------------|-------------------------------|
| API Gateway | **Kong** — jwt + rate limiting; identity comes from the `sub` claim, zero auth code in the service |
| Post/Follow/Feed as three services | One Spring Boot service, `posts-db` (Postgres); Post + Follow tables |
| DynamoDB partition/GSI design | Postgres indexes: `posts(author, created_at DESC)` and `follows(follower)` / `follows(followee)` |
| Precomputed feed table + SQS fan-out | **Deliberately skipped** (spec: "naive fan-out-on-read first; precompute only if it ever hurts") — one `JOIN` over followees ordered by `created_at` |
| Hybrid celebrity handling | Out of scope — no celebrities at local scale |
| Replicated Redis for viral posts | Out of scope — no caches until a concrete need appears |
| Cursor pagination by timestamp | **Adopted as-is**: `GET /posts/feed?cursor=&pageSize=`, cursor = oldest seen `createdAt` |

**The transferable lesson:** the naive fan-out-on-read design is the *correct starting point*, not a shortcut — the interview architecture is what it evolves into when read amplification actually hurts. Keeping the cursor contract (`timestamp` cursor, reverse-chron order) from day one means the API never changes when the implementation behind it does.

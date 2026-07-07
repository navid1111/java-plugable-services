# Ticketmaster — Architecture Breakdown

> Source: [Hello Interview — Ticketmaster](https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster)
> Maps to: **turf-service** (`/bookings`) in this repo — venues, time slots, bookings with no double-booking.

## 1. Requirements

### Functional (core)
1. Users view event details
2. Users search for events with filters
3. Users book tickets to events

Out of scope: booking history, admin event creation, dynamic pricing.

### Non-functional (core)
1. **Availability** for search/view, **consistency** for booking (no double bookings)
2. Extreme scale: 10M concurrent users on a single hot event
3. Search latency < 500ms
4. Read-heavy: ~100:1 read-to-write ratio

## 2. Core Entities

| Entity | Purpose |
|--------|---------|
| Event | Date, description, type, performer details |
| User | Platform participants |
| Performer | Artist/team performing |
| Venue | Physical location, capacity, seat map |
| Ticket | One seat for one event: seat details, price, status |
| Booking | Transaction grouping ticket purchases + payment status |

## 3. API

```
GET  /events/:eventId                          → Event + Venue + Performer + Ticket[]
GET  /events/search?keyword=&start=&end=&page= → Event[]
POST /bookings/:eventId  {ticketIds, payment}  → bookingId
```

The booking endpoint later splits into **reserve** then **confirm** steps.

## 4. High-Level Architecture

```
Clients
  ↓
Load Balancer → API Gateway (auth, rate limiting, routing)
  ↓
┌───────────────┬────────────────┬─────────────────┐
Event Service   Search Service   Booking Service ──→ Stripe
└───────┬───────┴───────┬────────┴────────┬────────┘
    PostgreSQL      Elasticsearch       Redis
 (events, venues,  (full-text index   (seat locks,
  tickets,          via CDC)           query cache,
  bookings)                            waiting queue)
```

- **Event Service**: reads event/venue/performer data, returns seat map.
- **Search Service**: filtered queries against the search index.
- **Booking Service**: reservation + payment; PostgreSQL for ACID guarantees.

**Key UX problem this design must solve:** a user fills in payment details only to find the seat was sold — hence temporary reservations.

## 5. Deep Dives

### 5.1 Seat reservation & locking (the consistency core)

| Approach | Idea | Verdict |
|----------|------|---------|
| Long-running DB locks (`SELECT FOR UPDATE` held ~5 min) | Hold row lock through checkout | ❌ Bad — locks strain the DB, crash/network edge cases leave lock state uncertain |
| Status + expiration + cron | `reserved` status with expiry; cron resets stale rows | ⚠️ OK — lag between expiry and reset; cron is a failure point |
| Implicit status via expiry check | Atomic update where `AVAILABLE OR (RESERVED AND expired)` | ✅ Great — short transactions, no cron; slightly slower reads |
| **Redis distributed lock with TTL** | `SET ticketId:userId NX EX 600`; lock self-expires; DB only knows available/booked | ✅ **Chosen** |

Trade-offs of the Redis lock: the read path must consult Redis to gray out reserved seats; if Redis dies, UX degrades but the DB still prevents double-booking (optimistic concurrency at final purchase); the TTL can expire mid-payment — use a generous TTL and extend it.

**Full booking flow:**
1. `POST /bookings` with ticketId → Booking Service acquires Redis lock (10-min TTL)
2. In-progress booking row written to Postgres; bookingId returned; user goes to payment page
3. Client tokenizes card via Stripe.js; server creates a PaymentIntent
4. Stripe **webhook** confirms payment → one DB transaction sets Ticket=`sold`, Booking=`confirmed`
5. Webhook handler is **idempotent** (bookingId as idempotency key)

### 5.2 Scaling reads (10M users on one event page)

- **Cache** static event data (`eventId → event object`) in Redis/Memcached — read-through, long TTL for static fields, invalidate on update.
- **Load-balance** across stateless Event Service instances; horizontal scaling is trivial because services hold no session state.

### 5.3 High-demand events (the Taylor Swift problem)

- *Good:* **SSE** pushes seat-map changes so users don't click dead seats.
- *Great:* **Virtual waiting queue** — a Redis sorted set (by arrival timestamp) gates entry to the booking page itself; users get live position/ETA over SSE; dequeued users are marked "admitted" (with TTL) and only admitted users may reserve.

### 5.4 Search < 500ms

- *Good:* B-tree indexes + query tuning — still weak for substring match.
- *Great #1:* Postgres full-text search (`tsvector` + GIN index).
- *Great #2 (chosen):* **Elasticsearch**, kept in sync from Postgres via **CDC**; inverted index + fuzzy matching. Cost: extra infra, sync complexity.

### 5.5 Caching search results

- Redis keyed on the normalized query string (24h TTL) — invalidation is fiddly.
- Better: Elasticsearch's built-in query caches + **CDN edge caching** for non-personalized results.

## 6. Storage choices

| Use case | Tech | Why |
|----------|------|-----|
| Events/Tickets/Bookings | PostgreSQL | ACID; row-level locking / OCC prevents double-booking |
| Reservations (locks) | Redis | Atomic `SET NX EX`, self-expiring TTL |
| Full-text search | Elasticsearch | Inverted index, fuzzy search |
| Hot-data cache & queue | Redis | In-memory speed, sorted sets |

## 7. Mapping to turf-service (this repo)

Interview scale ≠ our scale. What survives the translation:

| Interview design | turf-service (right-sized) |
|------------------|---------------------------|
| API Gateway (auth, rate limit) | **Kong** — jwt plugin + rate limiting already at the edge |
| Booking Service + Postgres ACID | Spring Boot + `bookings-db`; conflict check = a **unique constraint / transactional check on (venue, slot)** — the one piece we must get right |
| Redis TTL seat locks | **Not needed** — booking a turf slot is one atomic request, no multi-minute checkout to protect |
| Elasticsearch search | Plain SQL filtering on venues — no full-text need |
| Virtual waiting queue, SSE seat maps | Out of scope — no 10M-user flash sales |
| Stripe + idempotent webhooks | Deferred; if payments arrive later, copy the idempotency-key pattern |

**The transferable lesson:** consistency for the write path (no double-booking — enforce it in the database, not in application memory), availability for the read path. At our scale a Postgres constraint inside one transaction gives the same guarantee the whole Redis-lock apparatus provides at Ticketmaster's scale.

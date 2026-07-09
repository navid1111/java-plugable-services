# Interview Preparation Guide

This document contains technical, architectural, and project-based interview
questions based on the `turf-service` project.

The key theme to emphasize is that this service is small but serious: it uses a
database constraint to enforce the booking invariant under concurrency.

## 1. Project Architecture And Design Decisions

**Q: What does the turf service do?**

Answer: It manages turf venues, bookable time slots, and user bookings. Users
can browse venues and availability, book an available slot, list their own
bookings, cancel their bookings, and rebook slots after cancellation. The main
technical guarantee is that a slot cannot have more than one active booking,
even under concurrent requests.

**Q: Why is turf a separate microservice instead of being part of auth or chat?**

Answer: It owns a distinct business capability and a distinct database:
venues, slots, and bookings. Keeping it separate avoids coupling booking data
to authentication or messaging concerns. It can be mounted into different
products through Kong and its plug kit.

**Q: What does "plug kit" mean in this project?**

Answer: A plug kit is the reusable integration package a service ships with. In
`turf-service/plug`, it includes a compose fragment, a Kong setup script, and a
smoke test. A host project can include the service without editing service
source code.

**Q: Why does the service have its own `bookings-db`?**

Answer: Database ownership is the service boundary. Turf owns booking state and
can evolve its schema independently. Sharing the auth or Kong database would
couple unrelated services and make scaling, migrations, and security harder.

**Q: Why does Kong handle JWT verification instead of the turf service?**

Answer: JWT verification is a cross-cutting edge concern. Kong validates token
signature and expiration before requests reach backend services. This keeps
services focused on domain logic and gives a uniform security policy across
auth, posts, chat, and bookings.

**Q: If Kong verifies JWTs, why does `turf-service` still have `JwtHelper`?**

Answer: Kong verifies that the token is valid. The service still needs to know
which user is making the request, so it decodes the JWT payload and reads the
`sub` claim. `JwtHelper` is an identity extraction helper, not a signature
verification layer.

## 2. Booking Consistency And Concurrency

**Q: What is the most important invariant in the service?**

Answer: For a given `slot_id`, there can be at most one booking where
`status = 'active'`.

**Q: How is double-booking prevented?**

Answer: Postgres enforces it with a partial unique index:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uk_bookings_active_slot
ON bookings (slot_id)
WHERE status = 'active'
```

This allows many cancelled bookings for the same slot but only one active
booking.

**Q: Why not just check whether a slot is available before inserting?**

Answer: A check-then-insert flow has a race condition. Two requests can both
check availability at the same time, both see the slot as free, and both insert.
The database constraint is the final authority and prevents the second active
insert.

**Q: Why is a database unique index better than a Java `synchronized` block?**

Answer: A Java lock only protects one JVM process. If the service is scaled to
multiple instances, each instance has its own lock. A database unique index
protects the data across all instances and all clients.

**Q: Why use a partial unique index instead of `UNIQUE(slot_id)`?**

Answer: `UNIQUE(slot_id)` would allow only one booking ever for a slot, even if
the original booking was cancelled. The partial index applies only when
`status = 'active'`, so cancelled bookings remain as history while the slot
becomes bookable again.

**Q: Why does `BookingService.book()` call `saveAndFlush()`?**

Answer: `saveAndFlush()` forces the insert to hit the database inside the
service method. If the partial unique index rejects the insert, Spring raises
`DataIntegrityViolationException` immediately, and the service can map it to
`409 Conflict`.

**Q: What response should the second booking attempt receive?**

Answer: `409 Conflict`, because the request is syntactically valid and the slot
exists, but the requested state conflicts with the current booking state.

**Q: How does the smoke test prove concurrency safety?**

Answer: It starts three background `curl` requests against the same slot and
waits for all of them. It asserts exactly one `201 Created` and exactly two
`409 Conflict` responses. That proves concurrent attempts are resolved by the
database invariant.

**Q: What isolation level is required?**

Answer: The implementation relies on the unique index, not on serializable
transaction isolation. Postgres' normal uniqueness enforcement is enough to
reject conflicting active inserts. Higher isolation might be used for other
reasons, but it is not the core mechanism here.

## 3. API Design

**Q: What are the main endpoints?**

Answer:

- `GET /bookings/venues` - list venues with nested slots and availability
- `POST /bookings` - book a slot
- `GET /bookings/mine` - list bookings owned by the current token subject
- `DELETE /bookings/{id}` - cancel a booking

**Q: Why does `/bookings/mine` not accept a username query parameter?**

Answer: The current user is derived from the JWT `sub` claim. A query parameter
would let users attempt to list someone else's bookings by changing the URL.

**Q: Why is cancellation implemented as a status change instead of deleting the row?**

Answer: Status change preserves booking history and auditability. The row stays
in the database as `cancelled`, and the partial unique index no longer counts
it as active.

**Q: What should happen if Alice tries to cancel Bob's booking?**

Answer: The service returns `403 Forbidden`. The booking exists, but Alice does
not own it.

**Q: What should happen if Alice cancels the same booking twice?**

Answer: Both requests return `204 No Content`. The second cancellation is
idempotent because the booking is already cancelled.

**Q: What should happen when a user books a nonexistent slot?**

Answer: `404 Not Found`, because the referenced slot does not exist.

**Q: What should happen when a user books a past slot?**

Answer: `400 Bad Request`, because the slot exists but is no longer valid for a
new booking.

## 4. Java And Spring Boot Technical Questions

**Q: What is the role of `BookingController`?**

Answer: It owns HTTP concerns for `/bookings`: mapping paths and verbs,
parsing request bodies, reading headers, extracting identity, returning
`ResponseEntity`, and mapping domain exceptions to HTTP status codes.

**Q: What is the role of `BookingService`?**

Answer: It owns business rules: availability calculation, slot validation,
booking creation, conflict translation, ownership checks, cancellation, and
enriching booking views with venue and slot data.

**Q: What is `@Transactional` doing here?**

Answer: It wraps service methods in database transactions. `book()` and
`cancel()` need transactional writes. `listVenues()` and `mine()` use
read-only transactions for consistent read behavior and clear intent.

**Q: Why does the service define response records like `VenueView` and `BookingView`?**

Answer: They decouple API responses from JPA entities. This avoids leaking
internal persistence details and makes response fields explicit.

**Q: How do Spring Data repository methods work without implementation classes?**

Answer: Spring Data generates implementations at runtime from method names and
repository metadata. For example,
`findByUsernameOrderByCreatedAtDescIdDesc` becomes a query filtered by
username and ordered by creation time and ID.

**Q: Why use `JpaRepository` instead of writing SQL for everything?**

Answer: JPA repositories remove boilerplate for common CRUD and query patterns.
The service still uses SQL where it matters: creating the Postgres partial
unique index through `JdbcTemplate`.

**Q: Why is the partial unique index created with `JdbcTemplate` instead of a JPA annotation?**

Answer: Standard JPA does not portably express a Postgres partial unique index
with a `WHERE` clause. `JdbcTemplate` is used for this database-specific
invariant.

**Q: What does `@PrePersist` do in the entities?**

Answer: It sets `createdAt` automatically before a new entity is inserted. This
keeps timestamp assignment centralized in the entity lifecycle.

## 5. Security Questions

**Q: Where is authentication enforced?**

Answer: At Kong. The `/bookings` route has Kong's `jwt` plugin enabled, so
requests without valid JWTs are rejected before reaching the service.

**Q: What security risk exists if clients bypass Kong and call the service directly?**

Answer: `JwtHelper` only decodes the token payload; it does not verify the
signature. In production, the service should not be exposed directly to the
internet. It should only receive traffic from the trusted gateway or internal
network.

**Q: Why does `DELETE /bookings/{id}` need an ownership check if the user is already authenticated?**

Answer: Authentication proves who the user is. Authorization decides what that
user can do. Alice is authenticated, but she is not authorized to cancel Bob's
booking.

**Q: Why does the service store usernames instead of user IDs?**

Answer: In this project, JWT `sub` is the shared identity reference and
downstream services are decoupled from the auth database. Storing usernames
avoids a cross-service database dependency. In a larger system, the token might
use a stable user ID subject instead.

## 6. Gateway And Operations Questions

**Q: What does `turf-service/plug/kong-setup.sh` do?**

Answer: It creates or updates the Kong service for `turf-service`, creates the
`/bookings` route, enables the JWT plugin, and applies rate limiting.

**Q: Why is rate limiting configured in Kong?**

Answer: Rate limiting is a cross-cutting edge policy. Doing it in Kong protects
the backend before traffic reaches Java and keeps behavior consistent across
services.

**Q: How do you run the turf stack in the root project?**

Answer:

```bash
docker compose --profile turf up --build -d
./kong/setup-core.sh
./kong/setup-turf.sh
./turf-service/plug/smoke.sh
```

**Q: How do you prove the service is reusable outside the root project?**

Answer: Run the standalone demo in `examples/turf-standalone`. It includes the
auth and turf plug kits, configures Kong, and runs the same smoke test without
changing service source code.

## 7. Tradeoffs And Future Design

**Q: What are the main limitations of this version?**

Answer: It has no payments, no venue owner admin, no custom slot creation API,
no timezone-aware calendars, no waitlist, no notification system, and no search
or filtering. It is focused on the core booking invariant.

**Q: How would you add payments safely?**

Answer: Do not mark a booking active only after a slow payment call inside a
database transaction. A common design is to create a short-lived hold or pending
reservation, process payment asynchronously or through a payment intent, then
confirm or expire the hold. The database still needs a uniqueness guarantee for
active or held slots.

**Q: How would you handle very high contention for a popular slot?**

Answer: Keep the database unique index as the final invariant. Add user-facing
improvements such as queueing, temporary holds, backoff, clearer conflict
responses, or a waitlist. Caches and queues can improve experience, but they
should not replace the database guarantee.

**Q: How would you support multiple service instances?**

Answer: The current consistency mechanism already works across multiple
instances because Postgres enforces uniqueness. Operationally, you would also
need proper service discovery, observability, connection pool sizing, and
possibly a migration tool like Flyway or Liquibase for schema changes.

**Q: What would you change before production?**

Answer:

- use Flyway or Liquibase instead of creating indexes in `CommandLineRunner`
- hide service containers from public access
- use stable user IDs in JWT subjects
- add structured validation errors
- add integration tests in CI
- add monitoring for conflict rate and database errors
- add venue admin APIs and timezone-aware slot generation
- add payment or hold expiration if money is involved

## 8. Strong Summary Answer

If asked to summarize the service in an interview:

Answer: `turf-service` is a Spring Boot booking microservice behind Kong. Kong
handles JWT verification and rate limiting, while the service owns venues,
slots, and bookings in its own Postgres database. The main design choice is a
Postgres partial unique index on `bookings(slot_id) WHERE status='active'`,
which prevents double-booking under concurrent requests. The service exposes
simple `/bookings` APIs, maps conflicts to `409`, supports idempotent
cancellation, and ships a plug kit plus standalone smoke test to prove it can
be reused without source changes.

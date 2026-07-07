# Spring Boot Patterns and Code Analysis

The `turf-service` follows the same layered Spring Boot style as the earlier
services, with one especially important domain pattern:

```text
Do not check-and-insert in memory. Insert inside a transaction and let Postgres
enforce one active booking per slot.
```

That one choice is what makes the service safe under concurrent booking
requests.

## Core Spring Boot Patterns

1. **Layered Architecture**
   The code is split into controller, service, repository, model, config, and
   security packages.

2. **Constructor Injection**
   Controllers, services, and initializers receive dependencies through
   constructors. Dependencies are explicit and easy to test.

3. **Spring Data JPA Repository Pattern**
   Entity classes map to Postgres tables. Repository interfaces generate common
   queries from method names.

4. **Transactional Business Logic**
   `BookingService` owns transaction boundaries. Booking and cancellation are
   transactional because they change business state.

5. **Database-Enforced Consistency**
   `TurfDataInitializer` creates a partial unique index:

   ```sql
   CREATE UNIQUE INDEX IF NOT EXISTS uk_bookings_active_slot
   ON bookings (slot_id)
   WHERE status = 'active'
   ```

   This prevents double-booking even when multiple requests race.

6. **DTO Records**
   Request and response shapes use Java records such as
   `CreateBookingRequest`, `VenueView`, `SlotView`, and `BookingView`.

7. **Edge Authentication**
   Kong verifies JWT signatures and expiration. The service decodes the token
   payload only to read the username from `sub`.

## Package Structure

```text
com.example.turf
|-- config
|   `-- TurfDataInitializer.java
|-- controller
|   |-- BookingController.java
|   `-- HealthController.java
|-- model
|   |-- Booking.java
|   |-- Slot.java
|   `-- Venue.java
|-- repository
|   |-- BookingRepository.java
|   |-- SlotRepository.java
|   `-- VenueRepository.java
|-- security
|   `-- JwtHelper.java
|-- service
|   `-- BookingService.java
`-- TurfApplication.java
```

## File-by-File Breakdown

### 1. `TurfApplication.java`

This is the JVM entry point.

- **Pattern:** `@SpringBootApplication`
- **Why:** It enables Spring Boot auto-configuration, component scanning, and
  embedded Tomcat.

### 2. `controller/HealthController.java`

This exposes the service health endpoint.

- **Pattern:** Minimal REST controller.
- **Endpoint:** `GET /health`
- **Why:** Docker Compose uses this endpoint to know when `turf-service` is
  healthy enough for routing and smoke tests.

### 3. `controller/BookingController.java`

This exposes the public REST API under `/bookings`.

- **Pattern:** `@RestController`, `@RequestMapping("/bookings")`,
  `ResponseEntity`, and Java records for request bodies.
- **Endpoints:**
  - `GET /bookings/venues`
  - `POST /bookings`
  - `GET /bookings/mine`
  - `DELETE /bookings/{id}`
- **Detail:** Each endpoint receives the `Authorization` header, extracts the
  username through `JwtHelper`, and delegates behavior to `BookingService`.
- **Why:** The controller owns HTTP concerns: headers, request parsing, status
  codes, and JSON response shape. The service owns business rules.

The exception mapping is local and explicit:

- `ConflictException` -> `409 Conflict`
- `ForbiddenException` -> `403 Forbidden`
- `NotFoundException` -> `404 Not Found`
- `IllegalArgumentException` -> `400 Bad Request`

### 4. `service/BookingService.java`

This is the business core of the service.

- **Pattern:** `@Service` plus `@Transactional`.
- **Read operations:**
  - `listVenues()` is `@Transactional(readOnly = true)`.
  - `mine()` is `@Transactional(readOnly = true)`.
- **Write operations:**
  - `book()` is `@Transactional`.
  - `cancel()` is `@Transactional`.

Important methods:

- `listVenues()` loads venues, loads their slots, loads active bookings for
  those slots, and computes availability.
- `book()` validates the username and slot, rejects past slots, inserts an
  active booking, and maps database uniqueness failures to conflict.
- `mine()` lists bookings for the current token subject and enriches them with
  venue/slot details.
- `cancel()` verifies ownership and flips active bookings to cancelled.

The line that makes conflict detection deterministic is:

```java
bookings.saveAndFlush(new Booking(slot.getId(), currentUser));
```

`saveAndFlush` forces the database insert while still inside the service
method. That means a unique-index violation is raised immediately and can be
translated into `ConflictException`.

### 5. `config/TurfDataInitializer.java`

This runs at service startup.

- **Pattern:** `CommandLineRunner` plus `@Transactional`.
- **Responsibilities:**
  - create the partial unique index for active bookings
  - seed venues and future slots if no venues exist
- **Why:** JPA annotations cannot express a Postgres partial unique index in a
  portable way. The initializer uses `JdbcTemplate` for the database-specific
  invariant.

Seeded venues:

- `Northside Arena` in `Mirpur DOHS`
- `City Five Turf` in `Banani`
- `Riverside Sports Ground` in `Bashundhara`

Each venue receives three future one-hour slots.

### 6. `security/JwtHelper.java`

This reads the username from the JWT payload.

- **Pattern:** Minimal downstream identity helper.
- **Detail:** It reads the Bearer token, Base64URL-decodes the payload, parses
  JSON, and returns the `sub` claim.
- **Why:** Kong has already verified the signature and expiration before
  forwarding `/bookings` requests. The service needs the subject as an identity
  reference.

Important boundary: `JwtHelper` does not verify signatures. If traffic bypasses
Kong, the service can decode a token but cannot prove it was signed by the
trusted secret.

### 7. `model/Venue.java`

This is the JPA entity for a turf venue.

- **Pattern:** Object-Relational Mapping.
- **Fields:** `id`, `name`, `location`, `createdAt`.
- **Why:** Venue data is stable metadata used to group slots in the browse API.

### 8. `model/Slot.java`

This is the JPA entity for a bookable time range.

- **Pattern:** Entity with query-supporting indexes.
- **Fields:** `id`, `venueId`, `startTime`, `endTime`, `createdAt`.
- **Indexes:**
  - `idx_slots_venue_start`
  - `idx_slots_start_time`
- **Why:** Slots are separate from bookings so one slot can have a history of
  booking attempts and cancellations.

### 9. `model/Booking.java`

This is the JPA entity for reservation state.

- **Pattern:** Entity with lifecycle callback and status transition method.
- **Fields:** `id`, `slotId`, `username`, `status`, `createdAt`,
  `cancelledAt`.
- **Statuses:** `active`, `cancelled`.
- **Method:** `cancel()` changes active bookings to cancelled and stamps
  `cancelledAt`.

The entity does not delete rows when a booking is cancelled. Keeping cancelled
rows gives an audit trail and allows the partial unique index to free the slot
without losing history.

### 10. `repository/VenueRepository.java`

This repository provides venue reads.

- **Pattern:** Spring Data JPA method-name query.
- **Method:** `findAllByOrderByNameAscIdAsc()`
- **Why:** Venue listing should be stable and deterministic.

### 11. `repository/SlotRepository.java`

This repository provides slot reads.

- **Pattern:** Spring Data JPA method-name query.
- **Method:** `findByVenueIdInOrderByStartTimeAscIdAsc(...)`
- **Why:** `listVenues()` needs all slots for a batch of venue IDs, ordered by
  start time.

### 12. `repository/BookingRepository.java`

This repository provides booking reads and writes.

- **Pattern:** Extends `JpaRepository<Booking, Long>`.
- **Methods:**
  - `findByStatusAndSlotIdIn(...)`
  - `findByUsernameOrderByCreatedAtDescIdDesc(...)`
- **Why:** The service needs active bookings for availability and user-owned
  bookings for `/bookings/mine`.

### 13. `application.properties`

This configures the service.

- **Datasource URL:** `SPRING_DATASOURCE_URL`, defaulting to local Postgres.
- **Datasource username/password:** environment-driven.
- **JPA mode:** `spring.jpa.hibernate.ddl-auto=update`
- **Open Session In View:** disabled with `spring.jpa.open-in-view=false`

The service is Docker-friendly because Compose injects database connection
settings through environment variables.

### 14. `turf-service/plug/kong-setup.sh`

This is the Kong plug-kit script.

- **Creates:** service `turf-service`.
- **Routes:** `/bookings`.
- **Plugins:** `jwt`, `rate-limiting`.
- **Why:** A host project can mount turf without rewriting central gateway
  configuration by hand.

### 15. `turf-service/plug/compose.plug.yml`

This is the reusable compose fragment.

- **Defines:** `bookings-db` and `turf-service`.
- **Database:** isolated Postgres volume `turf_bookings_data`.
- **Health check:** `GET /health`.
- **Why:** The service ships with the infrastructure it owns.

### 16. `turf-service/plug/smoke.sh`

This is the executable proof of the service contract.

- **Flow:** register users, login, verify `401`, browse venues, book
  concurrently, verify conflicts, list mine, reject unauthorized cancel,
  cancel idempotently, rebook.
- **Why:** The service is not considered complete until it works through Kong,
  not just in isolated Java code.

## Important Implementation Decisions

### Database constraint instead of a Java lock

A Java lock only protects one JVM. It fails when the service has multiple
instances. A Postgres unique index protects the data no matter how many app
instances race to insert.

### Cancel by status, not delete

Deleting a booking would lose history. Updating `status` to `cancelled` keeps
the booking auditable while freeing the slot because the unique index only
covers active rows.

### Identity from token, not request parameters

`GET /bookings/mine` does not accept `?username=alice`. The username comes from
the token subject. This prevents one user from listing another user's bookings
by changing a URL.

### Service-level exception classes

The service defines small domain exceptions (`ConflictException`,
`ForbiddenException`, `NotFoundException`) so business outcomes can map cleanly
to HTTP without leaking database exceptions into the controller contract.

## Limitations And Future Evolution

This version intentionally omits:

- payments
- refunds
- venue owner admin APIs
- slot creation APIs
- timezone-aware venue calendars
- search and filtering
- notification workflows
- waitlists

Those can be added later without changing the central invariant: active booking
uniqueness must remain enforced in the database.

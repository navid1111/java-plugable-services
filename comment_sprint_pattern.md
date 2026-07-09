# Spring Boot Patterns and Code Analysis

The `comment-service` follows the same layered Spring Boot pattern as the
other resource services, while adding a reusable target-reference model.

## Core Spring Boot Patterns

1. **Layered Architecture**
   The service is split into controller, service, repository, model, and
   security helper layers.

2. **Constructor Injection**
   Controllers and services receive dependencies through constructors, making
   dependencies explicit and easy to test.

3. **Repository Pattern**
   Spring Data JPA handles ordinary persistence through repository interfaces.
   Native SQL is used only where cursor paging needs exact ordering.

4. **DTO Records**
   Request and response shapes are Java records inside the controller. This
   keeps HTTP payload contracts compact and immutable.

5. **Transactional Service Boundary**
   Business operations live in `CommentService`, with `@Transactional` on
   writes and `@Transactional(readOnly = true)` on reads.

## File-by-File Breakdown

### 1. `CommentApplication.java`

This is the JVM entry point.

- **Pattern:** `@SpringBootApplication`.
- **Why:** It enables auto-configuration, component scanning, and embedded
  Tomcat startup for the `com.example.comment` package.

### 2. `model/Comment.java`

This is the JPA entity for comments.

- **Pattern:** Object-Relational Mapping.
- **Detail:** It maps `targetType`, `targetId`, `authorUsername`, `content`,
  and `createdAt` to the `comments` table.
- **Why:** The service owns comment rows in `comments-db` and stores target
  resources by reference.

Important details:

- `@PrePersist` assigns `createdAt` on insert.
- The target index supports target-scoped pagination:
  `(target_type, target_id, created_at DESC, id DESC)`.
- The service stores no copied target data, such as post content or video
  title.

### 3. `repository/CommentRepository.java`

This repository handles target-scoped reads.

- **Pattern:** Spring Data repository with native query methods.
- **Detail:** The first-page query orders by `created_at DESC, id DESC`.
  The cursor query adds a strict "older than cursor" condition.
- **Why:** Cursor paging must be stable even when comments share the same
  timestamp.

### 4. `service/CommentService.java`

This class contains business logic.

- **Pattern:** `@Service` with transaction boundaries.
- **Detail:** It validates target keys, trims content, enforces the 500
  character content limit, clamps page size, encodes/decodes cursors, and
  protects owner-only delete.
- **Why:** Controllers stay thin, while domain rules stay close to persistence.

Important rules:

- `targetType` must start with a letter and may include letters, numbers,
  dots, underscores, and hyphens.
- `targetId` may include letters, numbers, dots, underscores, hyphens, and
  colons.
- The service does not call target services to check existence.

### 5. `controller/CommentController.java`

This class exposes the HTTP API.

- **Pattern:** `@RestController`, `@RequestMapping("/comments")`, and
  `ResponseEntity`.
- **Detail:** It extracts the username from the JWT header and delegates
  behavior to `CommentService`.
- **Why:** The controller owns HTTP concerns: status codes, request bodies,
  and response DTOs.

Endpoints:

- `POST /comments/targets/{targetType}/{targetId}`
- `GET /comments/{id}`
- `GET /comments/targets/{targetType}/{targetId}`
- `DELETE /comments/{id}`

### 6. `security/JwtHelper.java`

This helper decodes the JWT payload and reads the `sub` claim.

- **Pattern:** Minimal downstream identity helper.
- **Detail:** It does not verify token signatures.
- **Why:** Kong already verifies JWTs before forwarding protected
  `/comments` requests. The service only needs the username identity
  reference.

### 7. `controller/HealthController.java`

This exposes `/health`.

- **Pattern:** Simple readiness endpoint.
- **Why:** Docker Compose health checks can wait for the service before smoke
  tests or dependent setup steps run.

## Why the Generic Target Model Matters

The API shape:

```text
/comments/targets/{targetType}/{targetId}
```

means the comment service can attach to many resource types:

- `tweeter.post/123`
- `youtube.video/abc123`
- `turf.venue/7`

The target owner chooses the key. Comment-service only stores and indexes that
key. This avoids coupling comments to `tweeter-service` and keeps the service
portable across products.

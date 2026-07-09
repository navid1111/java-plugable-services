# Spring Boot Patterns and Code Analysis

The `tweeter-service` follows the same layered Spring Boot pattern established
by `auth-service`, while proving that a protected downstream service can trust
Kong for JWT verification.

## Core Spring Boot Patterns

1. **Layered Architecture**
   The service is split into controller, service, repository, model, and
   security helper layers.

2. **Constructor Injection**
   Controllers and services receive dependencies through constructors, making
   dependencies explicit and testable.

3. **Repository Pattern**
   Spring Data JPA generates standard persistence behavior from repository
   interfaces and method names.

4. **Right-Sized SQL**
   Simple CRUD uses Spring Data derived methods. Feed paging uses native SQL
   because the cursor query is clearer and needs exact ordering semantics.

## File-by-File Breakdown

### 1. `TweeterApplication.java`

This is the JVM entry point.

- **Pattern:** `@SpringBootApplication`.
- **Why:** It enables auto-configuration, component scanning, and embedded
  Tomcat startup for the `com.example.tweeter` package.

### 2. `model/Post.java`

This is the JPA entity for user posts.

- **Pattern:** Object-Relational Mapping.
- **Detail:** `authorUsername`, `content`, and `createdAt` map to the `posts`
  table.
- **Why:** The service owns post data in `posts-db` and stores the username as
  an identity reference.

### 3. `model/Follow.java`

This is the JPA entity for the follow graph.

- **Pattern:** Entity with unique constraint.
- **Detail:** `(follower_username, followee_username)` is unique.
- **Why:** Following the same user twice should be idempotent and must not
  create duplicate graph edges.

### 4. `repository/PostRepository.java`

This repository handles post reads and feed queries.

- **Pattern:** Spring Data repository with native query methods.
- **Detail:** `findByAuthorUsernameOrderByCreatedAtDescIdDesc` handles author
  listing. Native feed queries join `posts` to `follows` and use a composite
  cursor.
- **Why:** Feed paging must be stable across posts with identical timestamps.

### 5. `repository/FollowRepository.java`

This repository handles follow/unfollow writes.

- **Pattern:** Repository with a native idempotent insert.
- **Detail:** `INSERT ... ON CONFLICT DO NOTHING` makes follow idempotent.
- **Why:** The database enforces the unique edge guarantee directly.

### 6. `service/PostService.java`

This class contains business logic.

- **Pattern:** `@Service` with `@Transactional` boundaries.
- **Detail:** It validates content, prevents self-follow, clamps feed page size,
  and encodes/decodes feed cursors.
- **Why:** Business rules stay out of the controller and close to the data
  operations they coordinate.

### 7. `controller/PostController.java`

This class exposes the HTTP API.

- **Pattern:** `@RestController`, `@RequestMapping("/posts")`, and
  `ResponseEntity`.
- **Detail:** It extracts the username from the JWT header and delegates all
  business decisions to `PostService`.
- **Why:** The controller owns HTTP concerns; the service owns behavior.

### 8. `security/JwtHelper.java`

This helper decodes the JWT payload and reads the `sub` claim.

- **Pattern:** Minimal downstream identity helper.
- **Detail:** It does not verify signatures. Kong has already done that before
  forwarding protected `/posts` requests.
- **Why:** The downstream service needs only the username identity reference,
  not password or session logic.

# Spring Boot Patterns and Code Analysis

The `post-search-service` follows the same layered Spring Boot style as the
other resource services, while adding a small manual inverted-index model for
keyword search.

## Core Spring Boot Patterns

1. **Layered Architecture**
   Code is separated into controller, service, repository, model, and
   security helper packages.

2. **Constructor Injection**
   Controllers and services receive dependencies through constructors. This
   keeps dependencies explicit and makes the classes easier to test.

3. **Repository Pattern**
   Spring Data JPA handles persistence. Native SQL is used for exact
   search-and-sort queries where cursor ordering must be explicit.

4. **DTO Records**
   Request and response payloads are Java records inside the controller.
   This keeps the HTTP contract compact and immutable.

5. **Transactional Service Boundary**
   `PostSearchService` owns business logic and transaction boundaries. Writes
   are transactional; reads use `@Transactional(readOnly = true)`.

6. **Edge Authentication**
   Kong verifies JWT signatures. The service decodes the payload only to
   require an authenticated user and read the `sub` claim when needed.

## File-by-File Breakdown

### 1. `PostSearchApplication.java`

This is the JVM entry point.

- **Pattern:** `@SpringBootApplication`
- **Why:** Enables auto-configuration, component scanning, and embedded
  server startup for the `com.example.postsearch` package.

### 2. `model/SearchDocument.java`

This is the JPA entity for indexed document snapshots.

- **Pattern:** Entity per owned table.
- **Detail:** It stores `targetType`, `targetId`, `authorUsername`,
  `content`, `createdAt`, `likeCount`, and `indexedAt`.
- **Why:** Search needs enough data to return results without calling the
  post service.

Important details:

- Unique `(target_type, target_id)` makes ingestion idempotent.
- `replaceSnapshot()` updates source metadata and content.
- `updateLikeCount()` changes the ranking signal without changing terms.

### 3. `model/SearchTermEntry.java`

This entity represents one normalized term pointing to one document.

- **Pattern:** Manual inverted-index row.
- **Detail:** Each term/document pair is unique.
- **Why:** Searching by term should not scan every document body.

Example:

```text
content: "java spring java"
terms:   java, spring
```

Only two term rows are stored because terms are deduped per document.

### 4. `repository/SearchDocumentRepository.java`

This repository performs document lookup and search queries.

- **Pattern:** Spring Data repository with native SQL for search.
- **Detail:** Search queries select documents whose ids appear in grouped
  term matches.
- **Why:** Multi-term search needs AND semantics:

  ```sql
  HAVING COUNT(DISTINCT e.term) = :termCount
  ```

The repository has separate methods for:

- first page by recency
- next page by recency cursor
- first page by likes
- next page by likes cursor

### 5. `repository/SearchTermEntryRepository.java`

This repository deletes old term rows during re-indexing.

- **Pattern:** Repository method with `@Modifying` JPQL.
- **Why:** Upserting changed content should replace the document's term set,
  not append stale terms.

### 6. `service/Tokenizer.java`

This component normalizes text into searchable terms.

- **Pattern:** Stateless `@Component`.
- **Detail:** Lowercases with `Locale.ROOT`, splits on non-alphanumeric
  characters, removes blanks, and dedupes terms with insertion order.
- **Why:** Ingestion and query paths must use identical normalization.

### 7. `service/PostSearchService.java`

This class contains the search domain rules.

- **Pattern:** `@Service` with transaction boundaries.
- **Responsibilities:**
  - validate target keys
  - validate document fields
  - tokenize content and queries
  - upsert documents idempotently
  - replace term rows on re-index
  - update like count
  - clamp page size
  - encode and decode cursors
  - reject invalid sort and cursor values

This layer keeps controllers thin and prevents persistence details from
leaking into HTTP handlers.

### 8. `controller/PostSearchController.java`

This class exposes the HTTP API under `/post-search`.

- **Pattern:** `@RestController`, `@RequestMapping`, `ResponseEntity`.
- **Detail:** It maps service exceptions to HTTP status codes.
- **Why:** Controllers should own HTTP concerns, not search logic.

Endpoints:

- `PUT /post-search/documents/{targetType}/{targetId}`
- `GET /post-search/documents/{targetType}/{targetId}`
- `GET /post-search?q=&sort=&cursor=&pageSize=`
- `PUT /post-search/documents/{targetType}/{targetId}/like-count`

### 9. `security/JwtHelper.java`

This helper decodes the JWT payload and reads `sub`.

- **Pattern:** Minimal downstream identity helper.
- **Detail:** It does not verify the signature.
- **Why:** Kong already verifies protected `/post-search` requests. The
  service only needs to reject missing headers and extract identity context.

### 10. `controller/HealthController.java`

This exposes `/health`.

- **Pattern:** Lightweight health endpoint.
- **Why:** Docker Compose health checks can wait for the service before smoke
  tests run.

## Why Manual Indexing Instead of Full-Text Search?

The prompt explicitly rules out Elasticsearch and built-in Postgres full-text
search. The manual index is intentionally simple:

```text
SearchDocument 1 -> java
SearchDocument 1 -> spring
SearchDocument 2 -> java
SearchDocument 2 -> docker
```

For `q=java spring`, the service groups term rows by document id and requires
both terms to be present. This is enough for the current product goals and
keeps the design interview-sized.

## Why Store a Snapshot?

Search results must return useful data without calling `tweeter-service`.
That is why the document stores author, content, and createdAt. The tradeoff
is staleness: if a post changes, the composed app must re-index it. A future
event bus can automate this without changing the search boundary.

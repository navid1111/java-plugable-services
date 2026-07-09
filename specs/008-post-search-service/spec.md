# Feature Specification: post-search-service

**Feature Branch:** `008-post-search-service` | **Created:** 2026-07-09 | **Status:** Done
**Input:** Add Facebook-style keyword search for posts. The prompt assumes no
Elasticsearch and no built-in Postgres full-text index, so this service will
own a small manual inverted index. The final integration proof must compose
auth + posts + comments + post-search.

## User Scenarios & Testing

### User Story 1 - Ingest post documents (Priority: P1)

After a product creates a post, the composed app indexes that post into
post-search with enough metadata to make it searchable.

**Independent test:** Create a post through `tweeter-service`, then upsert a
search document into `post-search-service` using the returned post id,
content, author, and createdAt.

**Acceptance scenarios:**
1. **Given** alice created a post, **When** the app PUTs
   `/post-search/documents/tweeter.post/{postId}` with content,
   authorUsername, and createdAt, **Then** the document is stored and its
   searchable terms are indexed.
2. **Given** the same document is ingested again with changed content,
   **Then** the existing document and term rows are replaced idempotently,
   not duplicated.
3. **Given** empty content or a malformed target key, **Then** ingestion
   returns `400`.

### User Story 2 - Search posts by keyword (Priority: P1)

An authenticated user searches for posts containing one or more keywords.

**Independent test:** Ingest several documents with overlapping terms; query
single- and multi-word searches.

**Acceptance scenarios:**
1. **Given** posts containing "java spring" and "java docker", **When** GET
   `/post-search?q=java`, **Then** both posts return.
2. **Given** a multi-term query `java spring`, **When** search runs, **Then**
   only posts containing both normalized terms return.
3. **Given** blank query text, **Then** search returns `400`.
4. **Given** no matching posts, **Then** search returns `200` with an empty
   `items` list and `nextCursor=null`.

### User Story 3 - Sort and page search results (Priority: P1)

A user can choose whether matching posts are sorted by recency or like count,
and can page through stable results.

**Independent test:** Ingest matching posts with different createdAt values
and like counts; walk both sort orders with a small page size.

**Acceptance scenarios:**
1. **Given** matching posts with different creation times, **When** GET
   `/post-search?q=java&sort=recency`, **Then** newest posts return first.
2. **Given** matching posts with different like counts, **When** GET
   `/post-search?q=java&sort=likes`, **Then** highest-like posts return
   first, with recency and id tie-breakers.
3. **Given** a `nextCursor`, **When** the next page is requested with the
   same query and sort, **Then** strictly later results in that sort order
   return with no duplicates and no gaps.
4. **Given** an invalid cursor or unsupported sort, **Then** `400`.

### User Story 4 - Receive like-count updates (Priority: P2)

Post-search can receive like-count updates so `sort=likes` works without
owning the product's like feature.

**Acceptance scenarios:**
1. **Given** an indexed post, **When** PUT
   `/post-search/documents/tweeter.post/{postId}/like-count` with
   `likeCount=12`, **Then** later searches sorted by likes use 12.
2. **Given** a like-count update for a missing document, **Then** `404`.
3. **Given** a negative like count, **Then** `400`.

### User Story 5 - Integration demo: auth + post + comment + post-search (Priority: P2)

A Facebook-like host product composes auth, tweeter posts, reusable comments,
and post-search without changing any service code.

**Independent test:** `examples/post-search-standalone/` mounts auth,
tweeter, comment, and post-search plug kits. The smoke test creates a post,
comments on it, indexes it, updates its like count, searches for it, and
proves auth is enforced.

**Acceptance scenarios:**
1. **Given** a fresh host project, **When** auth + tweeter + comment +
   post-search plug kits are composed in and their `kong-setup.sh` scripts
   run, **Then** the smoke test can create a post, comment on target
   `tweeter.post/{postId}`, index/search the post, and sort by recency and
   likes through the host Kong.
2. **Given** the demo passed, **Then** zero lines of auth-service,
   tweeter-service, comment-service, or post-search-service source were
   modified.

### Edge Cases

- Query normalization is lowercase ASCII terms split on non-alphanumeric
  boundaries; fuzzy matching and stemming are out of scope.
- Stop-word removal is not required in v1.
- Multi-term queries use AND semantics.
- Documents with identical sort fields must page stably by id tie-breakers.
- Search results may be stale until the caller indexes the post document.
- Privacy, personalization, media search, and realtime search-page updates
  are out of scope.

## Requirements

### Functional Requirements

- **FR-001:** All endpoints live under `/post-search`; Kong applies jwt +
  rate limiting to the whole prefix.
- **FR-002:** `PUT /post-search/documents/{targetType}/{targetId}` upserts a
  post search document by reference. Body includes authorUsername, content,
  and createdAt.
- **FR-003:** Ingestion tokenizes content and writes a manual inverted index;
  no Elasticsearch, Lucene, or Postgres full-text search.
- **FR-004:** `GET /post-search?q=&sort=&cursor=&pageSize=` searches by
  normalized keyword terms with cursor paging.
- **FR-005:** Supported sort values are `recency` and `likes`.
- **FR-006:** `PUT /post-search/documents/{targetType}/{targetId}/like-count`
  updates stored likeCount for ranking. The source of truth for likes remains
  outside post-search.
- **FR-007:** Owns `post-search-db`; stores targetType, targetId,
  authorUsername, content snapshot, createdAt, likeCount, indexedAt, and
  term index rows only. It never reads `posts-db` or `comments-db`.
- **FR-008:** Ships a plug kit under compose profile `post-search`.

### Key Entities

- **SearchDocument** - id, targetType, targetId, authorUsername, content,
  createdAt, likeCount, indexedAt; unique targetType + targetId.
- **SearchTermEntry** - term, documentId; unique term + documentId.

## Success Criteria

- **SC-001:** A post created via tweeter can be indexed and found by keyword
  through Kong in the integration demo.
- **SC-002:** Multi-term searches only return documents containing all query
  terms.
- **SC-003:** `sort=recency` and `sort=likes` both page without duplicates or
  gaps.
- **SC-004:** `examples/post-search-standalone/smoke.sh` passes with auth +
  post + comment + post-search, and with zero service-code changes.
- **SC-005:** Review confirms no direct database access to `posts-db` or
  `comments-db`, no HTTP calls to tweeter/comment services, and no use of
  full-text search engines.

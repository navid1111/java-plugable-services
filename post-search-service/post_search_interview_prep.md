# Interview Preparation Guide

This document contains technical, architectural, and project-based interview
questions for `post-search-service`.

## 1. Architecture and Service Boundaries

**Q: Why is post-search a separate service instead of a feature inside tweeter-service?**

**Answer:** Search is a reusable capability. Keeping it separate lets a host
product index post-like documents from tweeter today and another service
later. It also keeps each service's database and ownership boundary clear.

**Q: Why does the search document use `targetType` and `targetId` instead of `postId`?**

**Answer:** `postId` would couple search to tweeter posts. A generic target
reference lets the service index `tweeter.post/123`, `youtube.video/abc`, or
another future resource without schema changes.

**Q: Why does post-search not call tweeter-service to fetch posts?**

**Answer:** The platform avoids casual service-to-service calls. Calling
tweeter during search would couple search availability to tweeter availability
and add runtime latency. Instead, the composed app pushes a snapshot into
search after creating or updating a post.

**Q: Why does search store content snapshots?**

**Answer:** Search results need author, content, and createdAt without joining
another service's database or calling another API. The tradeoff is staleness:
if the source post changes, it must be re-indexed.

**Q: What does the final integration demo prove?**

**Answer:** It proves auth, posts, comments, and search can be composed behind
Kong with zero service-code changes. A real post is created, commented on,
indexed, ranked by likes, and found by keyword.

## 2. Manual Inverted Index

**Q: Why not use Elasticsearch?**

**Answer:** The prompt explicitly asks for no Elasticsearch. The manual
inverted index is right-sized for this repo and demonstrates how search works
underneath: terms map to matching documents.

**Q: Why not use Postgres full-text search?**

**Answer:** The prompt also rules out built-in Postgres full-text indexing.
The implementation uses ordinary relational tables and btree indexes.

**Q: How does a multi-term query work?**

**Answer:** Query text is tokenized into normalized terms. The repository
groups term rows by document id and requires the count of distinct matched
terms to equal the query term count. That gives AND semantics.

**Q: What are AND semantics?**

**Answer:** `q=java spring` returns only documents containing both `java` and
`spring`. It does not return documents that contain only one of the terms.

**Q: Why dedupe terms per document?**

**Answer:** Repeating a word many times should not create duplicate term rows
for the same document. A unique `(term, document_id)` constraint keeps the
index compact and makes grouped matching correct.

## 3. Spring Boot and Java Questions

**Q: Why use a separate `Tokenizer` component?**

**Answer:** Ingestion and query paths must use the exact same normalization.
A dedicated component avoids duplicated tokenization rules and keeps the
service logic easier to read.

**Q: Why are request and response DTOs Java records?**

**Answer:** Records are concise immutable data carriers. They make the HTTP
contract visible without a lot of boilerplate.

**Q: Why is `PostSearchService` transactional?**

**Answer:** Upserting a document and replacing its term rows must happen as
one business operation. Search reads use `readOnly = true`; writes use a
regular transaction.

**Q: Why use native SQL in `SearchDocumentRepository`?**

**Answer:** Search queries need exact grouping, sort ordering, and cursor
predicates. Native SQL makes these rules explicit and predictable.

**Q: Why does `JwtHelper` not verify signatures?**

**Answer:** Kong verifies JWT signatures and expiration on `/post-search`
before forwarding requests. The service decodes the token only to read the
identity context and reject missing or malformed headers.

## 4. Cursor Paging and Sorting

**Q: Why does recency sorting include both `createdAt` and `id`?**

**Answer:** Multiple documents can have the same timestamp. Adding `id DESC`
creates a stable total order and prevents duplicates or gaps across pages.

**Q: Why does likes sorting include `likeCount`, `createdAt`, and `id`?**

**Answer:** `likeCount` is the primary ranking signal. `createdAt` breaks
ties by recency, and `id` breaks any remaining ties for stable paging.

**Q: Why encode cursors instead of exposing raw fields?**

**Answer:** The cursor is an implementation detail. Encoding keeps the API
compact and allows the service to validate that a cursor belongs to the
requested sort type.

**Q: What happens if a client sends a `likes` cursor with `sort=recency`?**

**Answer:** The service rejects it as an invalid cursor. Cursor format is tied
to the sort order.

## 5. Like Count and Ranking

**Q: Does post-search own likes?**

**Answer:** No. It stores only the current like count as a ranking signal. The
source of truth for individual likes remains outside search.

**Q: Why have a like-count update endpoint?**

**Answer:** It lets a host product push ranking changes into search without
making search own the like domain.

**Q: What happens if the like-count update targets a missing document?**

**Answer:** The service returns `404`, because it cannot rank a document that
has not been indexed yet.

**Q: Why reject negative like counts?**

**Answer:** Like counts represent totals and should be zero or greater. A
negative count signals invalid caller data.

## 6. Consistency, Scaling, and Future Evolution

**Q: What consistency problem exists in this design?**

**Answer:** Creating a post and indexing it are two separate writes. If the
post write succeeds and the index write fails, search misses the post until
re-indexed.

**Q: How would you improve consistency later?**

**Answer:** Add an event bus. The post service would emit post-created and
post-updated events, and search would consume them into its own database.

**Q: How would you scale popular search terms?**

**Answer:** Add stronger indexes, tune query plans, cache hot query pages, and
eventually partition term rows. If product requirements grow, move to a real
search engine.

**Q: How would you support stemming or fuzzy matching?**

**Answer:** Replace or extend the tokenizer and index model. For production
quality fuzzy search, a dedicated search engine would be the practical next
step.

**Q: How would deletes work?**

**Answer:** V1 has no delete endpoint. A future source-delete flow could call
a delete-document endpoint or publish a post-deleted event that search
consumes locally.

**Q: What would you test before production hardening?**

**Answer:** JWT rejection, target validation, content validation, idempotent
re-indexing, stale term cleanup, single-term search, multi-term search,
no-match search, cursor paging with tied sort fields, invalid cursors,
like-count updates, and the full auth + post + comment + search demo.

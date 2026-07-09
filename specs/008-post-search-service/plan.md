# Implementation Plan: post-search-service

**Branch:** `008-post-search-service` | **Date:** 2026-07-09
**Spec:** [spec.md](spec.md)

## Summary

New Spring Boot service for searching post-like documents by keyword. It is
post-focused but still reference-based: documents are keyed by
`targetType + targetId`, so the first integration uses `tweeter.post/{id}`
and later products can index their own post-like resources. The service owns
the index; it does not call the post service or comment service.

## Technical Context

- **Language/runtime:** Java 21, Spring Boot 4.1, same scaffold pattern as
  the existing resource services
- **Storage:** PostgreSQL `post-search-db`; tables for search documents and
  manual term entries
- **Indexing:** application-level tokenizer writes `SearchTermEntry` rows;
  btree indexes on `term`, `(target_type, target_id)`, sort columns
- **Identity:** JWT-decode helper copied from existing services; reads `sub`
  only after Kong verifies jwt
- **Gateway:** `/post-search` route + `jwt` plugin + rate limiting via plug
  kit
- **Compose profile:** `post-search`
- **Integration proof:** `examples/post-search-standalone/` composes auth +
  tweeter + comment + post-search

## Constitution Check

| Article | Status |
|---------|--------|
| I - one DB per service | Pass: `post-search-db` only |
| II - auth at the edge | Pass: Kong jwt on `/post-search`; service reads `sub` |
| III - identity by reference | Pass: stores usernames and target refs only |
| IV - plug kit | Pass: `post-search-service/plug/` exists |
| V - no service-to-service calls | Pass: no synchronous post/comment lookup |
| VI - single ownership | Pass: post-search owns index snapshots only, not posts/comments |
| VII - integration demo | Pass: `examples/post-search-standalone/` is green |
| VIII - right-sized | Pass: Postgres manual index; no Kafka/Redis/Elasticsearch yet |

## Design Decisions

1. **Explicit ingestion endpoint in v1.** The composed app or smoke test
   indexes posts after creating them. This keeps the platform's no casual
   service-to-service rule intact. The future scale path is a post-created
   event stream, but v1 avoids adding a broker.
2. **Manual inverted index.** Each normalized term becomes a row pointing to
   the document. Search joins/group-counts term rows and requires all query
   terms to match. This matches the prompt constraint: no search engine and
   no Postgres full-text search.
3. **Document snapshot.** The service stores content and metadata needed to
   render a search result. The post service remains source of truth; search
   is allowed to be stale until re-ingested.
4. **Like count is an indexed ranking signal, not ownership.** The update
   endpoint accepts the current count from an owning product/like flow.
   Search never owns individual likes.
5. **Cursor shape depends on sort.** `recency` cursor stores
   `(createdAt, documentId)`. `likes` cursor stores
   `(likeCount, createdAt, documentId)` to keep paging stable.
6. **No comments in the index.** The final integration includes comments to
   prove composition, but post-search v1 searches posts only.

## Risks

- **Dual-write staleness:** If the composed app creates a post but fails to
  index it, search misses it. Accepted in v1; solve with events when the
  product needs stronger guarantees.
- **Manual index query cost:** Multi-term queries require grouping term
  matches. Fine at this repo scale; future versions can shard terms, cache
  hot queries, or move cold terms.
- **Target namespace discipline:** Search documents depend on stable
  `targetType` naming such as `tweeter.post`.
- **JWT helper drift:** Same accepted duplication as other services; revisit
  only if helper behavior diverges.

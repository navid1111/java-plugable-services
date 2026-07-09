# Interview Preparation Guide

This document contains technical, architectural, and project-based interview
questions based on the `comment-service`.

## 1. Project Architecture and Design Decisions

**Q: Why is comment-service not nested under `/posts/{id}/comments`?**

**Answer:** The service is designed to be reusable across products. It owns the
`/comments` prefix and accepts generic target references like
`tweeter.post/123` or `youtube.video/abc123`. If it lived under `/posts`, it
would look like a feature owned by `tweeter-service`.

**Q: Why use `targetType` and `targetId` instead of a `postId` column?**

**Answer:** `postId` would couple comments to posts. The generic target key
lets the same service attach comments to posts, videos, venues, or future
resources without schema changes.

**Q: Why does comment-service not verify that the target exists?**

**Answer:** The platform avoids casual service-to-service calls. Calling
tweeter or a future YouTube service during comment creation would couple
comment writes to another service's availability. In v1, the composed app is
responsible for passing real target keys. If strict enforcement becomes
necessary, an event-backed target reference table would be a better next step.

**Q: What does the standalone demo prove?**

**Answer:** It proves comments can run with only auth and Kong. The demo writes
comments to `tweeter.post` and `youtube.video` target keys without running
tweeter or YouTube services, showing that comments are genuinely reusable.

**Q: Why are comments stored in their own database?**

**Answer:** The platform constitution requires one database per service. The
comment service owns `comments-db`; it never reads `posts-db`, `users-db`, or
another service's tables.

## 2. Java and Spring Boot Technical Questions

**Q: Why does `CommentService` have `@Transactional` annotations?**

**Answer:** Write methods like create and delete need transaction boundaries
around database operations. Read methods use `readOnly = true` to communicate
intent and allow persistence-layer optimizations.

**Q: Why use native SQL for target comment paging?**

**Answer:** Paging needs exact ordering by `created_at DESC, id DESC` and a
cursor predicate for rows strictly older than the last item. Native SQL makes
the ordering and tie-break behavior explicit.

**Q: Why does the cursor include both `createdAt` and `id`?**

**Answer:** Multiple comments can have the same timestamp. Including `id`
creates a total order, so page boundaries do not skip or duplicate comments.

**Q: Why use Java records in `CommentController`?**

**Answer:** Request and response payloads are immutable data carriers. Records
keep DTO definitions concise and make the HTTP contract easy to read.

**Q: Why does `JwtHelper` decode the token but not verify the signature?**

**Answer:** Kong's JWT plugin verifies the signature and expiration before
proxying `/comments` requests. The service only decodes `sub` to get the
current username.

## 3. API Behavior and Edge Cases

**Q: Can a client spoof `authorUsername`?**

**Answer:** No. The create request body only accepts `content`. The author is
always taken from the JWT `sub` claim.

**Q: What happens if Alice tries to delete Bob's comment?**

**Answer:** `CommentService.deleteOwn()` compares the current username with
the comment's `authorUsername`. If they differ, the service returns `403`.

**Q: What happens if the target has no comments?**

**Answer:** The target comment endpoint returns `200 OK` with an empty `items`
list and `nextCursor=null`.

**Q: What target key validation exists?**

**Answer:** `targetType` must start with a letter and may contain letters,
numbers, dots, underscores, or hyphens. `targetId` may contain letters,
numbers, dots, underscores, hyphens, or colons. Both have length limits.

**Q: Why hard delete comments instead of soft delete?**

**Answer:** V1 has no replies, moderation audit, or legal retention flow. Hard
delete keeps reads simple. If replies or moderation arrive, tombstones would
be worth revisiting.

## 4. Scaling and Future Evolution

**Q: How would you scale reads for a very popular target?**

**Answer:** First add indexes and read replicas. If one target becomes hot,
cache first pages by `(targetType, targetId)`, use short TTLs, and invalidate
or refresh on new comments. At larger scale, partition comments by target key.

**Q: How would you enforce target existence without coupling services?**

**Answer:** Add an event bus. Target-owning services emit target-created and
target-deleted events. Comment-service consumes those into a local reference
table and validates against local data.

**Q: How would replies change the design?**

**Answer:** Replies introduce hierarchy. A simple approach is adding
`parentCommentId` and using tombstones for deleted parents. For deep trees or
large threads, a separate thread model or materialized path may be needed.

**Q: How is this different from Facebook Live comments?**

**Answer:** This service is REST-only and optimized for persisted comments on
ordinary resources. It does not broadcast comments in realtime, maintain
viewer connections, or use WebSockets/SSE.

**Q: What would you test before production hardening?**

**Answer:** JWT rejection, rate limits, content validation, target key
validation, target isolation, cursor paging with tied timestamps, owner-only
delete, missing-comment `404`, and standalone plug-kit integration.

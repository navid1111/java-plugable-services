# Interview Preparation Guide

This document contains technical, architectural, and project-based interview
questions based on the `tweeter-service`.

## 1. Project Architecture and Design Decisions

**Q: Why does `tweeter-service` not validate JWT signatures itself?**

**Answer:** JWT verification is an edge responsibility handled by Kong's JWT
plugin. Kong rejects invalid or expired tokens before they reach
`tweeter-service`. The service only decodes the `sub` claim to know which
username is performing the action.

**Q: Why store `authorUsername` instead of a full copied user profile?**

**Answer:** The platform uses identity by reference. The auth service owns user
records, and tweeter stores only the username from the token. This avoids
duplicated profile data and keeps services independent.

**Q: Why are posts, follows, and feed in one service instead of three separate
services?**

**Answer:** At this project scale, splitting them would add operational
complexity without solving a real problem. The service owns one coherent
bounded context: posts and the follow graph used to read feeds.

**Q: What does the plug kit prove for `tweeter-service`?**

**Answer:** The plug kit proves the service can be mounted into another
Kong-fronted host project using only its image, Compose fragment, Kong setup
script, and smoke test. The standalone demo validates this with zero service
code changes.

## 2. Java and Spring Boot Technical Questions

**Q: Why use `@Transactional` in `PostService`?**

**Answer:** It defines transaction boundaries around write operations like
creating posts and follow edges. Read methods use `readOnly = true`, which
communicates intent and lets the persistence layer optimize where possible.

**Q: Why use a native SQL query for the feed?**

**Answer:** The feed query needs exact ordering and cursor semantics:
`created_at DESC, id DESC`, plus a composite cursor condition. Native SQL makes
that behavior explicit and easy to reason about.

**Q: How does the idempotent follow endpoint work?**

**Answer:** The database has a unique constraint on `(follower_username,
followee_username)`. The repository uses `INSERT ... ON CONFLICT DO NOTHING`,
so following the same person twice leaves one row and returns success.

**Q: Why does unfollow return success even if the edge does not exist?**

**Answer:** Unfollow is idempotent. After the request, the desired state is
"alice does not follow bob." If that was already true, the operation still
succeeded from the client's point of view.

## 3. Feed and Pagination

**Q: Why use cursor pagination instead of offset pagination?**

**Answer:** Cursor pagination is stable when new posts are inserted while a
user is paging. Offset pagination can skip or duplicate rows because the row
positions shift.

**Q: Why does the cursor include both `createdAt` and `id`?**

**Answer:** Timestamps can tie. Sorting and paging by `(created_at, id)` gives a
total order, so page boundaries do not drop or duplicate posts with identical
timestamps.

**Q: What is fan-out on read?**

**Answer:** The feed is built when the user reads it by joining posts against
the user's followees. This is simpler than precomputing feeds and is the right
starting point until read amplification becomes a real problem.

**Q: When would you move away from fan-out on read?**

**Answer:** If users follow many accounts or feed reads become too expensive,
the system could evolve toward precomputed feed rows, async fan-out workers, or
a hybrid approach for high-follower accounts.

## 4. Security and Boundaries

**Q: Can a client spoof `authorUsername` in the request body?**

**Answer:** No. `POST /posts` ignores any author field in the body. The author
comes from the JWT `sub` claim after Kong verifies the token.

**Q: Why allow following usernames that may not exist in auth?**

**Answer:** The service avoids service-to-service calls. It stores identity by
reference and does not ask auth to validate followees. That keeps services
loosely coupled.

**Q: What would you test before production hardening?**

**Answer:** JWT rejection, rate limits, post validation, self-follow rejection,
idempotent follow/unfollow, feed ordering, cursor paging with tied timestamps,
and standalone plug-kit integration.

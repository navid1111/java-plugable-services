# BFF / API Composition

The `bff` service composes client-shaped reads across owning services over HTTP. It **never**
touches another service's database and holds no domain rules — it fans out, applies deadlines,
and shapes the response.

## Composed endpoint

`GET /bff/posts/{id}` → `PostDetail`:

```json
{
  "post":    { "id": 1, "content": "…", "createdAt": "…", "updatedAt": "…", "version": 3 },
  "author":  { "username": "alice" },
  "comments": { "commentCount": 12 },
  "media":    { "mediaCount": 2 },
  "degraded": []
}
```

Sources (each an HTTP call to the owning service, Authorization + `traceparent` forwarded):

| Section | Owner | Route | Class |
|---------|-------|-------|-------|
| post, author | tweeter-service | `GET /posts/{id}` | **critical** |
| comments | comment-service | `GET /comments/targets/tweeter.post/{id}/summary` | optional |
| media | media-service | `GET /media/targets/tweeter.post/{id}/summary` | optional |

## Deadlines and parallelism

- Per-call connect/read timeouts (`bff.downstream.connect-timeout` / `read-timeout`) bound each
  hop; the overall `bff.composition.deadline` bounds the optional fan-out.
- The critical post fetch runs first (it gates existence/visibility). The optional summaries run
  in **bounded parallel** on a fixed, capped executor (`bff.composition.pool-size`, caller-runs
  on overflow) so composition can never exhaust threads.

## Partial-response policy

- **Critical dependency (post owner):**
  - missing → `404` (`post-not-found`)
  - deleted/tombstoned (visibility check) → `410` (`post-gone`)
  - failure/timeout → `502` (`critical-dependency-unavailable`, with a `dependency` field)
- **Optional dependency (comments, media):** if it fails or exceeds the deadline, its section is
  **omitted** (`null`) and its name is added to `degraded`. The overall response is still `200` —
  a degraded read beats a failed read. Clients inspect `degraded` to know what to retry or show
  as unavailable.

## Errors

All error responses are RFC 9457 `application/problem+json` with `type`, `title`, `status`,
`detail`, and (for `502`) the failing `dependency`.

## Tracing

The BFF forwards the inbound `traceparent` to every downstream call (generating a W3C root if
absent) and the inbound `Authorization`, so one trace and one identity span the composed request.

## Verification

`PostDetailCompositionTest` (WireMock-stubbed downstreams) covers: full composition in one call;
partial response when an optional dependency 500s; partial response when an optional dependency
exceeds the read timeout; and the 404 / 410 / 502 problem responses.

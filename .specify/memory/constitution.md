# Pluggable Services Platform — Constitution

**Version:** 1.0.0 | **Ratified:** 2026-07-07

Every feature spec, plan, and task list is checked against these articles.
A design that violates an article must either change or amend the
constitution via PR (bump the version, record the rationale).

## Article I — One database per service

Each service owns exactly one database. No service ever reads or writes
another service's tables. Schema changes are internal to the owner.

## Article II — Auth at the edge

Only auth-service knows passwords. Kong's `jwt` plugin verifies signature and
expiry on every protected route. Services read identity from the token's
`sub` claim and hold **zero** session state and **zero** auth code beyond a
JWT-decode helper.

## Article III — Identity by reference

Services store only `userId`/`username` foreign keys — never copies of
profile data. If richer display data is needed, call the owning service's
endpoint; don't replicate.

## Article IV — One path prefix, one plug kit

Each service owns a single path prefix (`/auth`, `/posts`, `/chat`,
`/bookings`) and ships a **plug kit** — the complete, self-contained
artifact needed to mount it on any Kong-fronted project:

```
<service>/plug/
├── compose.plug.yml   # the service + its database, profile-scoped
├── kong-setup.sh      # idempotent route/plugin registration (KONG_ADMIN_URL param)
└── smoke.sh           # end-to-end proof through the host's Kong
```

The plug kit references the service only as a **built Docker image**, never
its source tree.

## Article V — No casual service-to-service calls

Composition happens at the gateway and frontend layer. Composed UIs call
multiple services through Kong with one token. If services must react to
each other, that becomes an explicit event bus — added only when a concrete
need exists, never ad-hoc HTTP calls between services.

## Article VI — Single ownership of shared-looking data

Profile data lives with auth-service. The follow graph belongs to tweeter;
chat contacts belong to chat — different relationships even when they look
similar. No "shared" tables, no shared libraries for domain logic.

## Article VII — Done means integrated elsewhere (Integration Demo)

A service is not **done** until it has been plugged into a **separate host
project** — a directory outside the service's own source tree, with its own
`docker-compose.yml` and its own Kong instance — using only:

1. the service's published Docker image,
2. its plug kit (Article IV),
3. auth-service's plug kit when the service requires JWTs.

The demo must pass `smoke.sh` with **zero changes to service code**. Host
projects live under `examples/<service>-standalone/` and must be copyable
out of this repo verbatim.

## Article VIII — Right-sized scale

One node per service, Postgres everywhere. No queues, caches, or multi-node
topologies until a measured, concrete need appears. Interview-scale designs
(see `docs/architecture/`) document the evolution path; they are not the
starting point.

## Governance

- Amendments: PR that edits this file, bumps the version
  (MAJOR = article removed/redefined, MINOR = article added, PATCH = wording),
  and states the rationale in the PR body.
- Every `specs/NNN-*/plan.md` contains a **Constitution Check** section
  listing each article with a pass/deviation note.

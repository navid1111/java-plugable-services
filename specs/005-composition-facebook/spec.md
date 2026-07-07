# Feature Specification: composition demo ("facebook" = tweeter + chat)

**Feature Branch:** `005-composition-facebook` | **Created:** 2026-07-07 | **Status:** Draft
**Input:** Prove the platform's core claim — two independently built services
compose into one product with zero code changes, one command, one login.

> No separate plan.md: this feature writes no service code by design. If it
> needs any, that's a constitution violation (Art. V/VII), not a plan item.

## User Scenarios & Testing

### User Story 1 — One command assembles the product (Priority: P1)

An operator runs a single compose command and gets a working
"facebook": gateway + auth + posts + chat.

**Acceptance scenarios:**
1. **Given** a clean checkout, **When**
   `docker compose --profile tweeter --profile chat up` runs (plus the plug
   kits' kong-setup scripts), **Then** `/auth`, `/posts`, and `/chat` all
   answer through Kong.
2. **Given** the assembly is up, **When** turf's profile is *not* enabled,
   **Then** `/bookings` does not exist at the gateway — composition is
   subtractive too.

### User Story 2 — One login, both services (Priority: P1)

A user logs in once on a minimal static page and both posts and chat work
with that single token.

**Acceptance scenarios:**
1. **Given** the static page served locally, **When** alice logs in, creates
   a post, and sends a chat message, **Then** both succeed using the same
   Bearer token via Kong.
2. **Given** the token expires or is absent, **Then** both `/posts` and
   `/chat` uniformly return `401` from Kong.

### Edge Cases

- Bringing profiles up in either order must work (no startup coupling).
- Stopping the chat profile must leave tweeter fully functional.

## Requirements

- **FR-001:** Assembly uses only compose profiles + plug kits already
  shipped by features 001–003; **zero changes** in any service.
- **FR-002:** Frontend is one static HTML page (no framework, no build) that
  stores the token and calls `/posts` and `/chat` through Kong.
- **FR-003:** The page lives outside every service (e.g., `frontend/` or
  `examples/facebook/`) — services stay UI-free.

## Success Criteria

- **SC-001:** `git diff` on all service directories is empty after the demo
  works.
- **SC-002:** Demo recorded in README: the exact commands + a screenshot or
  transcript of post + chat with one token.

# Feature Specification: hardening

**Feature Branch:** `006-hardening` | **Created:** 2026-07-07 | **Status:** Draft
**Input:** Everything required before the stack runs anywhere non-local.
Config-and-infra only; service business logic untouched.

## User Scenarios & Testing

### User Story 1 — Secrets out of the repo (Priority: P1)

An operator clones the repo, generates secrets locally, and nothing
sensitive is committed.

**Acceptance scenarios:**
1. **Given** a fresh clone, **When** the operator copies `.env.example` to
   `.env` and generates a random JWT secret, **Then** the full stack works
   and `git grep` finds no real secret in tracked files.
2. **Given** the JWT secret changes, **Then** only `.env` changes — auth
   issuing and Kong verification both pick it up.

### User Story 2 — HTTPS at the gateway (Priority: P2)

All traffic can use TLS terminated at Kong.

**Acceptance scenarios:**
1. **Given** a self-signed cert, **When** clients call `https://…:8443`,
   **Then** all existing smoke flows pass over TLS.

### User Story 3 — Asymmetric signing evaluated (Priority: P3)

Decide RS256 vs HS256 with a written rationale: with RS256, Kong holds only
the public key — a gateway compromise cannot mint tokens.

**Acceptance scenarios:**
1. **Given** the evaluation, **Then** a decision (adopt or defer, with
   reasons) is recorded in this feature's plan notes; if adopted, all plug
   kits' jwt config updates and every standalone demo still passes.

## Requirements

- **FR-001:** `.env` for JWT secret + all DB passwords; `.env.example`
  committed; real values git-ignored.
- **FR-002:** Kong TLS listener on `:8443` with local self-signed cert.
- **FR-003 (optional):** decK declarative config replacing imperative
  kong-setup scripts — only if it keeps plug kits self-contained
  (Art. IV); otherwise defer with rationale.

## Success Criteria

- **SC-001:** `git grep -iE 'secret|password'` over tracked files returns
  only variable names/examples.
- **SC-002:** All four services' smoke scripts pass over HTTPS.
- **SC-003:** Every `examples/*-standalone/` demo still passes after
  hardening (regression gate for Art. VII).

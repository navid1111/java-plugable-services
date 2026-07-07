# Tasks: hardening

**Input:** [spec.md](spec.md)
**Prerequisite:** features 001–005 complete (this hardens what exists).

## Phase 1 — User Story 1: secrets (P1)

- [ ] T001 [US1] Introduce `.env` + committed `.env.example`; move JWT
      secret, issuer, and all DB passwords; git-ignore `.env`
- [ ] T002 [US1] Generate a real random secret (`openssl rand -base64 48`);
      purge defaults from compose files and kong scripts
- [ ] T003 [US1] **Checkpoint:** full stack + all smoke scripts green from a
      simulated fresh clone; SC-001 grep clean

## Phase 2 — User Story 2: HTTPS (P2)

- [ ] T004 [US2] Self-signed cert + Kong TLS listener `:8443`
- [ ] T005 [US2] Point smoke scripts at HTTPS (curl `-k` locally)
- [ ] T006 [US2] **Checkpoint:** SC-002 — all services' smokes pass over TLS

## Phase 3 — User Story 3: RS256 evaluation (P3)

- [ ] T007 [US3] Spike: auth signs RS256 with private key; Kong jwt plugin
      configured with public key only
- [ ] T008 [US3] Write the adopt/defer decision + rationale into
      `specs/006-hardening/notes.md`; if adopted, update every plug kit's
      jwt config

## Phase 4 — Optional: declarative gateway config

- [ ] T009 Evaluate decK export of current Kong state vs. per-service
      kong-setup scripts; adopt only if plug kits stay self-contained
      (Art. IV), else record deferral

## Phase 5 — Regression gate

- [ ] T010 **Checkpoint (feature exit):** rerun every
      `examples/*-standalone/` demo → SC-003

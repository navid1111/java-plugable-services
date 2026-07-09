# Implementation Plan: leetcode-service

**Branch:** `010-leetcode-service` | **Date:** 2026-07-09
**Spec:** [spec.md](spec.md)

## Summary

New Spring Boot service for managing coding problems, evaluating user solutions in isolated Docker containers, and maintaining live leaderboards for coding competitions. The service maintains its own PostgreSQL database, `leetcode-db`, and does not communicate directly with other databases or services.

## Technical Context

- **Language/runtime:** Java 21, Spring Boot 4.1, same scaffold structure as the other services.
- **Storage:** PostgreSQL `leetcode-db`; schemas for problems, submissions, competitions, competition_problems, and registrations. Uses JSONB fields for language code stubs and test cases.
- **Dynamic Execution Engine:**
  - Installs `docker-cli` inside the runtime image of `leetcode-service`.
  - Mounts `/var/run/docker.sock` from the host to the `leetcode-service` container in compose.
  - Spawns runner containers via standard input (`docker run --rm -i ...`) and intercepts stdout/stderr.
  - Constructs wrapper programs containing both user's code and a language-specific test harness.
  - Test harness outputs JSON results which are parsed by the Java application.
- **Identity:** JWT-decode helper extracted from existing services; reads `sub` claim for authenticated requests.
- **Gateway:** `/leetcode` prefix route configured on Kong with JWT plugin and rate-limiting plugin.
- **Compose profile:** `leetcode`
- **Integration proof:** `examples/leetcode-standalone/` composes auth-service + leetcode-service + Kong.

## Constitution Check

| Article | Status | Rationale |
|---------|--------|-----------|
| I - one DB per service | Pass | `leetcode-db` only. No cross-database queries. |
| II - auth at the edge | Pass | `/leetcode` submit and admin endpoints require JWT verification at Kong; service decodes `sub`. |
| III - identity by reference | Pass | Stores username strings as references. |
| IV - plug kit | Pass | `leetcode-service/plug/` includes `compose.plug.yml`, `kong-setup.sh`, and `smoke.sh`. |
| V - no service-to-service calls | Pass | Zero synchronous calls to auth-service or other services. |
| VI - single ownership | Pass | Owns problems, submissions, and competition data. |
| VII - integration demo | Pass | `examples/leetcode-standalone/` is planned to verify compilation, execution, and leaderboards. |
| VIII - right-sized scale | Pass | Postgres-based DB; simple container executions instead of persistent VM pools or queue/worker clusters in v1. |

## Design Decisions

1. **Stdout/Stdin IPC over Mounts:** To avoid aligning filesystem paths between the host, the `leetcode-service` container, and the dynamic execution containers, we run execution containers in interactive mode (`-i`) and pipe the combined code + test harness runner script directly via stdin (`Process.getOutputStream()`).
2. **Postgres JSONB for Flexibility:** Problem test cases and code stubs vary dramatically. Rather than a complex relational schema for inputs, outputs, and stub mappings, PostgreSQL `JSONB` column type is utilized.
3. **Language Wrapper Strategy:**
   - **Python:** Run `python -` directly on Alpine image.
   - **JavaScript:** Run `node -` directly on Node Alpine image.
   - **Java:** Run `sh -c "cat > SolutionRunner.java && javac SolutionRunner.java && java SolutionRunner"` on openjdk Alpine/slim image.
4. **Leaderboard Tiebreaker:** Scoring ranks users by the count of distinct solved problems. Ties are broken using the maximum submission timestamp of correct solutions within the competition window (`MAX(submittedAt)`), representing the completion time.
5. **No DB-Backed Queue in v1:** Submissions are processed synchronously in thread pool executors. This meets the simple scale requirements and respects Article VIII (right-sized scale). High execution spikes are queued in-memory via Java executor pools.

## Risks

- **Host resource exhaustion:** Runaway code could consume host CPU/Memory. Mitigated by setting Docker bounds (`--cpus="0.5"`, `-m "128m"`) and forcing a process-level timeout (5 seconds).
- **Security vulnerabilities in Docker Socket:** Mounting `/var/run/docker.sock` exposes the host Docker daemon. The service container should be configured securely.
- **Docker-in-Docker cold start latency:** Pulling runtime images (`python:3.11-alpine`, etc.) during the first submission causes latency. Mitigated by pulling required runner images during service initialization.

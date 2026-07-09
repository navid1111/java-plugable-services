# Tasks: leetcode-service

**Input:** [spec.md](spec.md), [plan.md](plan.md)
**Prerequisite:** feature 001 complete (auth-service) for the integration demo.

## Phase 1 - Setup

- [ ] T001 Scaffold `leetcode-service/` from the existing Spring Boot service pattern; own artifactId, Dockerfile, health endpoint, port 8080 internal
- [ ] T002 Update `leetcode-service/Dockerfile` runtime stage to install `docker-cli` (`apk add --no-cache docker-cli`) and configure root/group permissions if needed
- [ ] T003 [P] Add `leetcode-db` + `leetcode-service` to root compose under profile `leetcode`, mounting `/var/run/docker.sock` to the service container
- [ ] T004 [P] Copy JWT-decode helper into `leetcode-service/src/.../security/`

## Phase 2 - Database Entities & Repositories

- [ ] T005 [US1] Create entities for `Problem`, `Submission`, `Competition`, `CompetitionProblem` and repositories with Hibernate schema mapping
- [ ] T006 [US1] Create dynamic startup seeder to populate default problems (e.g. "Two Sum", "Reverse String", "Max Depth of Binary Tree") with code stubs and test cases if DB is empty

## Phase 3 - Sandboxed Container Execution Engine

- [ ] T007 [US2] Write the execution runner interface and implementation that spawns a process for `docker run --rm -i --network none --cpus="0.5" -m "128m"`
- [ ] T008 [US2] Implement python test-wrapper builder (combines user solution + JSON test cases + evaluation runner script) and piping to stdin
- [ ] T009 [US2] Implement javascript/node test-wrapper builder and piping to stdin
- [ ] T010 [US2] Implement java test-wrapper builder (which compiles and executes runner in docker)
- [ ] T011 [US2] Add timeout enforcement (5 seconds) using process wait timeout; destroy running container and return `TIME_LIMIT_EXCEEDED`
- [ ] T012 [US2] Write standard output JSON parsers to map results back to `Submission` entity status and metrics
- [ ] T013 [US2] **Checkpoint:** Direct unit tests proving Python/JS/Java runner evaluations for normal, wrong, throwing, and infinite-loop cases

## Phase 4 - Problem & Submission REST APIs

- [ ] T014 [US1] `GET /leetcode/problems?page=1&limit=100` returning paginated partial problem representations
- [ ] T015 [US1] `GET /leetcode/problems/{id}` returning full problem description and language code stubs
- [ ] T016 [US2] `POST /leetcode/problems/{id}/submit?competitionId=` parsing auth JWT, validating input, and launching container execution async/sync
- [ ] T017 [US2] **Checkpoint:** Verify viewing problems, submitting code, and getting evaluation feedback directly through service port

## Phase 5 - Competition & Leaderboard REST APIs

- [ ] T018 [US3] Add `POST /leetcode/competitions` to create competitions for administrative/test convenience
- [ ] T019 [US3] Implement leaderboard database query with aggregation: count unique solved problems within start/end window, break ties by `MAX(submittedAt)`
- [ ] T020 [US3] `GET /leetcode/leaderboard/{competitionId}?page=1&limit=100` returning ranked competitors list
- [ ] T021 [US3] **Checkpoint:** Verify competition creation and leaderboard sorting with tiebreakers works as expected

## Phase 6 - Plug Kit & Gateway Integration

- [ ] T022 [P] `leetcode-service/plug/kong-setup.sh` - registering `/leetcode` route, JWT plugin, and rate limiting in Kong
- [ ] T023 [P] `leetcode-service/plug/compose.plug.yml` - compose file mapping `leetcode-service` image, database, and socket mount
- [ ] T024 [P] `leetcode-service/plug/smoke.sh` - service-level smoke script running register/login via auth, fetching problems, submitting code, verifying statuses and leaderboard
- [ ] T025 Thin gateway script wrapper `kong/setup-leetcode.sh` delegating to plug kit

## Phase 7 - Integration Demo (Art. VII)

- [ ] T026 [US4] `examples/leetcode-standalone/docker-compose.yml`: mounts auth plug kit, leetcode plug kit, and host `/var/run/docker.sock`
- [ ] T027 [US4] `examples/leetcode-standalone/README.md` with walkthrough commands
- [ ] T028 [US4] `examples/leetcode-standalone/smoke.sh`: end-to-end integration demo showing user registration -> login -> fetch problems -> submit passing python solution -> verify leaderboard rank through Kong API Gateway
- [ ] T029 [US4] **Checkpoint (feature exit):** Integration smoke green with zero service code modifications

## Dependencies

- T001 and T002 block execution development.
- Phase 2 and Phase 3 can run in parallel but both block Phase 4.
- Phase 4 blocks Phase 5 and Phase 6.
- Phase 6 blocks Phase 7.

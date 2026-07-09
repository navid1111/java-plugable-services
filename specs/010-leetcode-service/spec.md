# Feature Specification: leetcode-service

**Feature Branch:** `010-leetcode-service` | **Created:** 2026-07-09 | **Status:** Draft
**Input:** Add a LeetCode-like service that lists coding problems, supports coding/submitting solutions in multiple languages, runs execution in isolated Docker containers, and manages competition leaderboards.

## User Scenarios & Testing

### User Story 1 - View Problems List & Detail (Priority: P1)

Users (authenticated or guest) can browse a paginated list of programming problems, and select a problem to view its details, tags, difficulty, and skeleton code stubs in multiple languages.

**Independent test:** Populate a few programming problems (e.g., "Two Sum", "Reverse String"); retrieve the list with pagination, and fetch details for a single problem.

**Acceptance scenarios:**
1. **Given** problems are seeded in the database, **When** GET `/problems?page=1&limit=10` is called, **Then** a list of problems containing `id`, `title`, `difficulty`, and `tags` is returned (excluding description, codeStubs, and testCases for efficiency).
2. **Given** a problem exists with ID `two-sum`, **When** GET `/problems/two-sum` is called, **Then** the full problem detail is returned, including the description, difficulty, tags, and `codeStubs` map (e.g., python, javascript, java skeleton code).
3. **Given** a request for a non-existent problem ID, **Then** the server returns `404 Not Found`.

### User Story 2 - Submit Solution and Run Code (Priority: P1)

An authenticated user submits a code solution for a problem in a supported language. The service executes the code against hidden test cases in a sandboxed, resource-constrained container, and returns the result (PASSED, WRONG_ANSWER, RUNTIME_ERROR, TIME_LIMIT_EXCEEDED, etc.) within 5 seconds.

**Independent test:** Submit correct, wrong, throwing, and infinite-loop solutions for `two-sum` in Python, JavaScript, and Java. Verify results and execution times.

**Acceptance scenarios:**
1. **Given** an authenticated user, **When** POST `/problems/two-sum/submit` is called with correct Python code, **Then** the service runs it in a short-lived `python:3.11-alpine` container, returns a `Submission` object with status `ACCEPTED`, and logs the correct runtime.
2. **Given** code with a syntax/compilation error, **When** submitted, **Then** the service returns status `COMPILE_ERROR` along with compiler messages in `errorMessage`.
3. **Given** code with an infinite loop, **When** submitted, **Then** the service kills the execution container after 5 seconds and returns status `TIME_LIMIT_EXCEEDED`.
4. **Given** code attempting a security breach (e.g. accessing `/etc` or fetching a webpage), **When** submitted, **Then** the execution fails or times out due to read-only container mount, no-network policy (`--network none`), and standard execution bounds.

### User Story 3 - Competition Leaderboard (Priority: P1)

Users can query a live leaderboard for a specific competition. The leaderboard ranks users by the number of unique problems solved during the competition duration. In case of a tie, users are ranked by the total time taken to complete their solved set (represented by the timestamp of the last correct submission).

**Independent test:** Seed a competition and problems. Register users, submit passing solutions at different timestamps, and fetch the leaderboard to verify correct rank order.

**Acceptance scenarios:**
1. **Given** a competition `comp-1` starting at 18:00 and ending at 19:30, **When** GET `/leaderboard/comp-1` is called, **Then** it returns users sorted by distinct problems solved descending, and then by their last solve time ascending.
2. **Given** user Alice solved 2 problems (last at 18:20) and Bob solved 2 problems (last at 18:35), **Then** Alice is ranked higher than Bob.
3. **Given** submissions sent *outside* the competition start/end window, **Then** they are not counted towards the competition leaderboard.

---

## Requirements

### Functional Requirements

- **FR-001:** All endpoints live under `/leetcode`; Kong applies jwt verification on protected endpoints (submit/register/competition actions) and rate limits to the whole prefix.
- **FR-002:** `GET /problems?page=1&limit=100` retrieves problem list (paginated, partial fields).
- **FR-003:** `GET /problems/{id}` retrieves full problem statement, description, metadata, and code stubs.
- **FR-004:** `POST /problems/{id}/submit` runs the user's code in a sandboxed Docker container, evaluates test cases, logs the result in `submissions`, and returns the run details.
- **FR-005:** Supported languages: `python` (executes in `python:3.11-alpine`), `javascript` (executes in `node:20-alpine`), and `java` (compiles & executes in `openjdk:21-slim`).
- **FR-006:** `GET /leaderboard/{competitionId}?page=1&limit=100` retrieves paginated rankings for a competition.
- **FR-007:** Owns `leetcode-db` (PostgreSQL); no direct database access to other service tables.
- **FR-008:** Ships a plug kit under compose profile `leetcode`.

### Non-Functional & Security Requirements

- **SR-001 (Sandbox Isolation):** Each run spawns a container with `--network none`, `--cpus="0.5"`, `-m "128m"`, and short-lived execution.
- **SR-002 (Execution Timeout):** The container execution must be forcefully killed if it exceeds a 5-second timeout limit.
- **SR-003 (Read-Only Filesystem):** Container filesystems should be read-only where possible, or limit access to host directory mounts by using standard stdin pipe input.
- **SR-004 (Edge Identity):** Identity of the submitter is extracted from the `Authorization` header (`sub` claim) populated by the Kong gateway.

### Key Entities

- **Problem**
  - `id` (String, PK - e.g. "two-sum")
  - `title` (String)
  - `description` (Text)
  - `difficulty` (Enum: EASY, MEDIUM, HARD)
  - `tags` (JSONB)
  - `codeStubs` (JSONB - language to code stub template)
  - `testCases` (JSONB - input/expected output lists)

- **Submission**
  - `id` (Long, PK, Auto-increment)
  - `problemId` (String)
  - `username` (String)
  - `code` (Text)
  - `language` (String)
  - `status` (Enum: PENDING, ACCEPTED, WRONG_ANSWER, TIME_LIMIT_EXCEEDED, RUNTIME_ERROR, COMPILE_ERROR)
  - `passedCount` (Integer)
  - `totalCount` (Integer)
  - `executionTimeMs` (Integer)
  - `errorMessage` (Text)
  - `competitionId` (String, Nullable)
  - `submittedAt` (Timestamp)

- **Competition**
  - `id` (String, PK)
  - `title` (String)
  - `startTime` (Timestamp)
  - `endTime` (Timestamp)

- **CompetitionProblem**
  - `competitionId` (String, Composite PK)
  - `problemId` (String, Composite PK)
  - `problemOrder` (Integer)

## Success Criteria

- **SC-001:** Standard problems can be viewed, code can be submitted, and results are stored/returned correctly.
- **SC-002:** Compilation errors, incorrect outputs, runtime exceptions, and infinite loops are handled securely and returned as correct execution statuses.
- **SC-003:** Container bounds (no network, limited CPU/Memory, timeout) are verified and prevent resource depletion or server compromise.
- **SC-004:** The competition leaderboard correctly ranks users by unique solved count and solve time, ignoring submissions outside the window.
- **SC-005:** `examples/leetcode-standalone/smoke.sh` passes successfully with Kong, auth-service, and leetcode-service.

# LeetCode System Design - Interview Prep

This document captures the architectural trade-offs and common deep dive questions when designing a LeetCode-style remote code execution platform.

## Q: Why use PostgreSQL instead of a NoSQL database like DynamoDB?
**Context:** Many system design tutorials suggest NoSQL for LeetCode's problem test cases because schemas vary between languages.
**Answer:** While NoSQL provides schema flexibility, modern PostgreSQL supports first-class `JSONB` column types. Using Postgres allows us to leverage complex aggregations (e.g. Leaderboard tie-breakers) securely using ACID transactions while maintaining flexibility for our code stubs and test cases. Furthermore, sticking to Postgres adheres to our organizational architectural guidelines (Constitution Art. VIII), simplifying maintenance and operations without sacrificing functionality.

## Q: How do you achieve strict isolation when running user code?
**Answer:** User code is executed inside ephemeral Docker containers.
- **Network Isolation:** The container is run with `--network none` so it cannot make outbound requests or port-scan internal microservices.
- **Resource Constraints:** We limit CPU cycles (`--cpus=0.5`) and memory (`-m 128m`) to prevent fork bombs or memory exhaustion attacks.
- **Filesystem Isolation:** Instead of mounting a host directory, the test harness and user code are piped directly to the container via `stdin`. The container writes to `stdout` and exits cleanly, leaving zero host artifacts.

## Q: What if a user submits an infinite loop?
**Answer:** The execution layer in the Java application handles the process lifecycle using `ProcessBuilder`. The thread executing the submission invokes `process.waitFor(5, TimeUnit.SECONDS)`. If the process doesn't terminate within the window, the thread forcefully kills the container using `process.destroyForcibly()` and logs a `TIME_LIMIT_EXCEEDED` status.

## Q: Why mount the Docker Socket instead of using Docker-in-Docker (DinD) or AWS Lambda?
**Answer:** Mounting `/var/run/docker.sock` allows the lightweight `leetcode-service` container to command the host Docker Daemon to spawn sibling containers. DinD carries significant privilege escalation risks and networking complexities. While AWS Lambda provides excellent managed isolation, our system architecture demands a platform-agnostic, containerized on-premise execution engine that runs locally in Docker Compose.

## Q: How does the Leaderboard scale for 100k users?
**Answer:** Currently, we compute the leaderboard dynamically from PostgreSQL using a fast index on `(competitionId, status)`. As concurrent load scales up during the end of a competition, repeated aggregation queries will bottleneck the DB.
**Optimization path:** We would introduce a **Redis Sorted Set (ZSET)**. When a correct submission arrives, a background worker increments the user's score in the sorted set. The API would then poll Redis in `O(log(N))` time, delivering sub-millisecond leaderboard rendering even under extreme load.

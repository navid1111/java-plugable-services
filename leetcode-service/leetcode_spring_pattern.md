# Spring Boot & Database Patterns

## PostgreSQL JSONB Mapping without Libraries

While specialized libraries like `hypersistence-utils` exist for mapping JSONB in Hibernate, `leetcode-service` utilizes Jackson's `@JsonRawValue` to achieve lightweight, native JSON handling.

```java
@Column(columnDefinition = "jsonb")
@JsonRawValue
private String testCases;
```

When saved, Spring Data JPA inserts the raw JSON string natively into Postgres. When serialized to the client, `@JsonRawValue` prevents Jackson from escaping the string quotes, causing the payload to be emitted as native JSON objects.

## Strategy Pattern for Code Execution

The service employs the **Strategy Pattern** for the `CodeRunner` interface.
- A core `DockerProcessRunner` acts as a common utility for interacting with the local Docker Daemon.
- Language-specific runners (`PythonRunner`, `JavascriptRunner`) implement `CodeRunner`.
- The `LeetcodeController` dynamically selects the runner using `.filter(r -> r.supports(request.language))`.

## Native Aggregate Queries in Spring Data

For complex leaderboard logic requiring GROUP BY, DISTINCT, and MAX aggregations, the service uses JPQL via the `@Query` annotation. Spring Data Projects the results directly into a custom interface (`LeaderboardRow`), avoiding manual `ResultSet` mapping.

```java
@Query(
    "SELECT s.username AS username, COUNT(DISTINCT s.problemId) AS solvedCount, MAX(s.submittedAt) AS lastSolveTime " +
    "FROM Submission s ... " +
    "GROUP BY s.username ORDER BY solvedCount DESC"
)
List<LeaderboardRow> getLeaderboard(@Param("competitionId") String competitionId);
```

## Startup Fixture Seeding

The service uses an `@EventListener(ApplicationReadyEvent.class)` in the `DatabaseSeeder` component to populate default coding problems if the database is empty, removing the need for manual SQL scripts in development.

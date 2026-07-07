# Spring Boot Patterns and Code Analysis

The `auth-service` codebase follows standard Spring Boot conventions, with
clear separation of concerns and constructor-based dependency injection.

## Core Spring Boot Patterns

1. **Layered Architecture**
   The application is divided into `model`, `repository`, `service`, and
   `controller` layers.

2. **Dependency Injection**
   Spring manages object lifecycles as beans and injects dependencies through
   constructors.

3. **Convention over Configuration**
   Spring Boot auto-configuration and Spring Data JPA eliminate most raw JDBC
   and infrastructure boilerplate.

## File-by-File Breakdown

### 1. `DemoApplication.java`

This is the JVM entry point.

- **Pattern:** `@SpringBootApplication`.
- **Why:** It enables auto-configuration, component scanning, and embedded
  Tomcat startup for the `com.example.demo` package.

### 2. `model/User.java`

This is a JPA entity.

- **Pattern:** Object-Relational Mapping.
- **Detail:** `@Entity` and `@Table(name = "users")` map the class to the
  Postgres `users` table.
- **Why:** The service can interact with database records as Java objects.

### 3. `repository/UserRepository.java`

This is a Spring Data repository.

- **Pattern:** Repository Pattern.
- **Detail:** It extends `JpaRepository<User, Long>`. No implementation class
  is written manually.
- **Why:** Spring Data generates runtime implementations from method names like
  `findByUsername`.

### 4. `service/JwtService.java`

This service mints and verifies JWTs.

- **Pattern:** `@Service` stereotype and external configuration via `@Value`.
- **Detail:** The JWT secret, issuer, and expiration are injected from
  properties backed by environment variables.
- **Why:** Secrets and runtime configuration stay outside application code.

### 5. `controller/AuthController.java`

This class exposes the HTTP API.

- **Pattern:** `@RestController`, `@RequestMapping`, and constructor injection.
- **Detail:** `@RequestBody` deserializes JSON into the `Credentials` record.
- **Why:** The controller owns web concerns, delegates persistence to the
  repository, and formats HTTP responses with `ResponseEntity`.

# Interview Preparation Guide

This document contains technical, architectural, and project-based interview
questions based on the `auth-service`.

## 1. Project Architecture and Design Decisions

**Q: Why use an API Gateway like Kong instead of handling routing and rate
limiting directly inside Spring Boot?**

**Answer:** Kong handles cross-cutting edge concerns such as routing, rate
limiting, JWT verification, and TLS termination. That keeps Spring Boot focused
on business logic and gives every downstream service a consistent security
boundary.

**Q: Explain the Plug Kit pattern implemented in this project.**

**Answer:** A plug kit is the reusable integration package owned by each
service. For auth, `auth-service/plug` contains the Compose fragment, Kong
route setup script, and smoke test. A host project can mount the service
without copying service code.

**Q: Why do the Auth Service and Kong Gateway have separate Postgres
databases?**

**Answer:** They own different data. Kong stores routing and plugin
configuration; auth stores user credentials. Separate databases preserve
microservice boundaries and allow each component to migrate, secure, and scale
independently.

## 2. Java and Spring Boot Technical Questions

**Q: How does dependency injection work in this Spring Boot application?**

**Answer:** Spring creates beans in its IoC container. When `AuthController` is
created, Spring sees constructor parameters like `JwtService` and
`UserRepository`, finds matching beans, and injects them automatically.
Constructor injection makes dependencies explicit and easier to test.

**Q: You have an interface `UserRepository`, but no implementation class. How
does `findByUsername` work?**

**Answer:** Spring Data JPA creates a proxy implementation at runtime. It parses
the method name `findByUsername` and generates a query equivalent to selecting
users by their `username` column.

**Q: What is the difference between `@Controller` and `@RestController`?**

**Answer:** `@RestController` combines `@Controller` and `@ResponseBody`,
meaning return values are serialized directly to JSON rather than treated as
view names.

## 3. Security and JWT

**Q: What is a JWT and what are its three parts?**

**Answer:** A JSON Web Token is a stateless token format made of a header,
payload, and signature. The header describes the algorithm, the payload carries
claims like `sub`, `iss`, and `exp`, and the signature proves the token was not
tampered with.

**Q: How is token validation handled in this architecture?**

**Answer:** The `/auth` route is public because clients need it to register and
log in. The `/auth/me` endpoint validates the token app-side. Downstream
services are protected by Kong's JWT plugin, so requests should reach them only
after edge validation succeeds.

**Q: Why hash passwords with BCrypt instead of storing plain text or using
MD5?**

**Answer:** Plain text is catastrophic if leaked. MD5 is too fast and vulnerable
to brute-force and rainbow-table attacks. BCrypt includes salts and is
intentionally slow, making offline password cracking much harder.

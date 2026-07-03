# Spring Boot — A Beginner's Guide

A practical reference for this project (`com.example.demo`, Spring Boot 4.1.0, Java 21, Maven).
Read top-to-bottom the first time; after that, use it as a lookup.

---

## Table of Contents

1. [What Spring Boot Actually Does](#1-what-spring-boot-actually-does)
2. [Core Concepts (read this first)](#2-core-concepts-read-this-first)
3. [Project Structure — Where Code Goes](#3-project-structure--where-code-goes)
4. [The Layered Architecture](#4-the-layered-architecture)
5. [Annotations Cheat Sheet](#5-annotations-cheat-sheet)
6. [Writing Code: A Full Example (Layer by Layer)](#6-writing-code-a-full-example-layer-by-layer)
7. [Configuration (application.properties)](#7-configuration-applicationproperties)
8. [Adding a Database (JPA)](#8-adding-a-database-jpa)
9. [Validation & Error Handling](#9-validation--error-handling)
10. [Testing](#10-testing)
11. [Running & Building](#11-running--building)
12. [Common Pitfalls](#12-common-pitfalls)
13. [Naming & Style Conventions](#13-naming--style-conventions)

---

## 1. What Spring Boot Actually Does

**Spring** is a framework that manages your objects for you. **Spring Boot** is Spring
with sensible defaults so you can start instantly — no XML, an embedded web server, and
auto-configuration.

The single biggest idea to understand: **you don't create your objects with `new`.**
You declare classes, tag them with annotations, and Spring creates them, wires them
together, and hands them to you. This is called **Dependency Injection (DI)**.

```java
// You almost never do this in Spring:
UserService service = new UserService(new UserRepository());

// Instead you declare the need, and Spring provides it:
public UserController(UserService service) { ... }  // Spring passes it in
```

---

## 2. Core Concepts (read this first)

Understanding these 4 terms makes everything else click.

| Term | Meaning |
|------|---------|
| **Bean** | Any object that Spring creates and manages for you. |
| **IoC Container** | The "factory" Spring uses to create and store all beans (also called the *ApplicationContext*). |
| **Dependency Injection** | Spring automatically supplies (injects) the beans a class needs. |
| **Auto-configuration** | Spring Boot looks at your classpath (jars) and configures things automatically. Add the web starter → you get a web server, no setup. |

### How a bean gets created and injected

```java
@Service                      // 1. This annotation tells Spring: "manage this class"
public class GreetingService {
    public String greet() { return "Hi"; }
}

@RestController
public class GreetingController {
    private final GreetingService service;

    // 2. Spring sees the constructor needs a GreetingService,
    //    finds the bean it made in step 1, and passes it in automatically.
    public GreetingController(GreetingService service) {
        this.service = service;
    }
}
```

> **Rule of thumb:** inject dependencies through the **constructor** and store them in
> `private final` fields. This is the recommended, modern style.

---

## 3. Project Structure — Where Code Goes

Spring Boot doesn't force a folder layout, but this **package-by-layer** structure is the
most common and what most tutorials assume:

```
src/main/java/com/example/demo/
├── DemoApplication.java          # Entry point — do not move this out of the base package
├── controller/                   # Handles HTTP requests (the "web" layer)
│   └── UserController.java
├── service/                      # Business logic
│   └── UserService.java
├── repository/                   # Database access
│   └── UserRepository.java
├── model/  (or entity/)          # Data classes / DB tables
│   └── User.java
├── dto/                          # Request/response shapes (Data Transfer Objects)
│   ├── CreateUserRequest.java
│   └── UserResponse.java
├── config/                       # Configuration classes (@Configuration)
│   └── AppConfig.java
└── exception/                    # Custom exceptions + global handler
    └── GlobalExceptionHandler.java

src/main/resources/
├── application.properties        # App configuration
├── static/                       # Static files (css, js, images) served directly
└── templates/                    # Server-rendered HTML (Thymeleaf), if used

src/test/java/com/example/demo/   # Tests mirror the main package structure
```

> ⚠️ **Critical rule:** Keep `DemoApplication.java` in the **top-level package**
> (`com.example.demo`). Spring only scans for your components *underneath* the package
> where the main class lives. If you put a controller in `com.other.stuff`, Spring won't
> find it and your endpoint returns 404.

---

## 4. The Layered Architecture

Requests flow **down** through layers; data flows **back up**. Each layer has one job.

```
   HTTP Request
        │
        ▼
┌───────────────┐   @RestController   →  Talks HTTP. Parses request, returns response.
│  Controller   │                        NO business logic here.
└───────┬───────┘
        ▼
┌───────────────┐   @Service          →  The brains. Business rules, validation,
│    Service    │                        orchestration, transactions.
└───────┬───────┘
        ▼
┌───────────────┐   @Repository       →  Talks to the database. CRUD only.
│  Repository   │
└───────┬───────┘
        ▼
    Database
```

**Why separate them?** Each layer can be tested and changed independently. Swapping your
database only touches the repository; changing your API only touches the controller.

---

## 5. Annotations Cheat Sheet

Annotations (`@Something`) are how you communicate with Spring. Here are the ones you'll
use constantly.

### Startup
| Annotation | Put it on | What it does |
|-----------|-----------|--------------|
| `@SpringBootApplication` | Main class | The "on switch." Bundles 3 annotations: enables auto-config + component scanning. |

### Declaring beans (component stereotypes)
These all tell Spring "manage this class." The name just documents its role.
| Annotation | Put it on | Role |
|-----------|-----------|------|
| `@Component` | Any class | Generic managed bean. |
| `@Service` | Service classes | Business-logic bean. |
| `@Repository` | Data-access classes | DB bean (also translates DB exceptions). |
| `@RestController` | Web classes | Controller whose methods return JSON/data. |
| `@Controller` | Web classes | Controller that returns HTML view names (Thymeleaf). |
| `@Configuration` | Config classes | Holds `@Bean` factory methods. |
| `@Bean` | A method inside `@Configuration` | Manually register the returned object as a bean. |

### Web / REST mapping (used in controllers)
| Annotation | What it does |
|-----------|--------------|
| `@GetMapping("/path")` | Handle HTTP GET. Also: `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`. |
| `@RequestMapping("/base")` | Class-level base path shared by all methods. |
| `@PathVariable` | Bind a URL segment: `/users/{id}` → `id`. |
| `@RequestParam` | Bind a query param: `/users?active=true` → `active`. |
| `@RequestBody` | Bind the JSON request body to a Java object. |
| `@ResponseStatus(HttpStatus.CREATED)` | Set the HTTP status code returned. |

### Dependency injection
| Annotation | What it does |
|-----------|--------------|
| (constructor) | Preferred. If a class has one constructor, Spring injects automatically — no annotation needed. |
| `@Autowired` | Explicitly requests injection. Mostly optional now; avoid on fields. |
| `@Value("${my.prop}")` | Inject a value from `application.properties`. |
| `@Qualifier("name")` | Pick a specific bean when several of the same type exist. |

### JPA / database (covered in section 8)
`@Entity`, `@Id`, `@GeneratedValue`, `@Table`, `@Column`, `@OneToMany`, `@ManyToOne`.

### Validation (section 9)
`@Valid`, `@NotNull`, `@NotBlank`, `@Size`, `@Email`, `@Min`, `@Max`.

---

## 6. Writing Code: A Full Example (Layer by Layer)

Let's build a simple `User` feature end to end. This is the exact pattern you'll repeat
for almost every feature. (This example uses an in-memory list so it runs without a
database — swap in JPA later using section 8.)

### 6.1 The model — `model/User.java`
A plain data class representing a user.

```java
package com.example.demo.model;

public class User {
    private Long id;
    private String name;
    private String email;

    public User() {}  // needed for JSON deserialization

    public User(Long id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }

    // getters and setters (required for JSON serialization)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
```
> 💡 **Lombok** can eliminate all this boilerplate: annotate the class with `@Data` and
> the getters/setters/constructors are generated for you. Add the `lombok` dependency first.

### 6.2 The repository — `repository/UserRepository.java`
Stores and retrieves users. (In-memory here for simplicity.)

```java
package com.example.demo.repository;

import com.example.demo.model.User;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class UserRepository {
    private final Map<Long, User> store = new HashMap<>();
    private final AtomicLong idGenerator = new AtomicLong();

    public User save(User user) {
        if (user.getId() == null) {
            user.setId(idGenerator.incrementAndGet());
        }
        store.put(user.getId(), user);
        return user;
    }

    public Optional<User> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<User> findAll() {
        return new ArrayList<>(store.values());
    }
}
```

### 6.3 The service — `service/UserService.java`
Business logic. Note the constructor injection of the repository.

```java
package com.example.demo.service;

import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserService {
    private final UserRepository repository;

    // Spring injects UserRepository here automatically.
    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public User createUser(String name, String email) {
        User user = new User(null, name, email);
        return repository.save(user);
    }

    public User getUser(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    public List<User> getAllUsers() {
        return repository.findAll();
    }
}
```

### 6.4 The controller — `controller/UserController.java`
Maps HTTP requests to service calls.

```java
package com.example.demo.controller;

import com.example.demo.model.User;
import com.example.demo.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users")   // every path below is prefixed with this
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    // GET /api/users
    @GetMapping
    public List<User> list() {
        return service.getAllUsers();
    }

    // GET /api/users/1
    @GetMapping("/{id}")
    public User getOne(@PathVariable Long id) {
        return service.getUser(id);
    }

    // POST /api/users   with JSON body {"name":"Ann","email":"a@x.com"}
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public User create(@RequestBody User user) {
        return service.createUser(user.getName(), user.getEmail());
    }
}
```

### 6.5 Try it
```bash
mvn spring-boot:run

# in another terminal:
curl -X POST http://localhost:8080/api/users \
     -H "Content-Type: application/json" \
     -d '{"name":"Ann","email":"ann@example.com"}'

curl http://localhost:8080/api/users
curl http://localhost:8080/api/users/1
```

**That's the whole loop.** Controller → Service → Repository → Model. Every feature you
build repeats this shape.

---

## 7. Configuration (application.properties)

Located at `src/main/resources/application.properties`. Key/value settings that Spring
Boot reads on startup.

```properties
# Server
server.port=8080                     # port the app listens on
spring.application.name=demo

# Logging
logging.level.root=INFO
logging.level.com.example.demo=DEBUG # more detail from your own code

# Custom values — inject with @Value("${app.greeting}")
app.greeting=Hello there
```

Inject a custom value anywhere:
```java
@Value("${app.greeting}")
private String greeting;
```

> There's also `application.yml` (YAML) as an alternative format — same settings, nested
> syntax. Pick one; don't use both.

### Profiles (environment-specific config)
Create `application-dev.properties`, `application-prod.properties`, then activate one:
```properties
spring.profiles.active=dev
```

---

## 8. Adding a Database (JPA)

**JPA** (via Hibernate) maps Java classes to database tables so you write almost no SQL.

### 8.1 Add dependencies to `pom.xml`
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>          <!-- in-memory DB, great for learning -->
    <scope>runtime</scope>
</dependency>
```

### 8.2 Configure the DB in `application.properties`
```properties
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=update    # auto-creates tables from your @Entity classes
spring.h2.console.enabled=true          # browse the DB at http://localhost:8080/h2-console
```

### 8.3 Turn the model into an `@Entity`
```java
package com.example.demo.model;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // auto-increment
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String email;

    // constructors, getters, setters...
}
```

### 8.4 Replace the repository with a Spring Data interface
This is the magic part — **you write an interface, Spring writes the implementation.**
```java
package com.example.demo.repository;

import com.example.demo.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // Free methods you get automatically:
    //   save(), findById(), findAll(), deleteById(), count() ...

    // Custom queries by method name — Spring generates the SQL:
    Optional<User> findByEmail(String email);
    List<User> findByNameContaining(String text);
}
```
No `@Repository` needed on interfaces — Spring detects it. Your service code from 6.3
works unchanged.

---

## 9. Validation & Error Handling

### 9.1 Validation
Add the starter:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```
Annotate a request object and mark the parameter with `@Valid`:
```java
public class CreateUserRequest {
    @NotBlank(message = "name is required")
    private String name;

    @Email(message = "must be a valid email")
    private String email;
    // getters/setters
}

@PostMapping
public User create(@Valid @RequestBody CreateUserRequest req) { ... }
```
If validation fails, Spring returns `400 Bad Request` automatically.

### 9.2 Global exception handling
Instead of try/catch in every controller, handle errors in one place:
```java
package com.example.demo.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice                 // applies to ALL controllers
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
}
```

---

## 10. Testing

Tests live under `src/test/java/...` mirroring your main packages.

```java
// Unit test a service in isolation (no Spring, fast)
class UserServiceTest {
    @Test
    void createsUser() {
        UserRepository repo = new UserRepository();
        UserService service = new UserService(repo);

        User u = service.createUser("Ann", "ann@x.com");

        assertNotNull(u.getId());
        assertEquals("Ann", u.getName());
    }
}
```

```java
// Integration test the web layer with a running context
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void listsUsers() throws Exception {
        mockMvc.perform(get("/api/users"))
               .andExpect(status().isOk());
    }
}
```
Run with `mvn test`.

Useful test annotations: `@SpringBootTest` (full context), `@WebMvcTest` (web layer only),
`@DataJpaTest` (repository layer only), `@MockBean` (replace a bean with a mock).

---

## 11. Running & Building

```bash
mvn spring-boot:run                 # run in dev (auto-restart with devtools)
mvn test                            # run all tests
mvn clean package                   # build a runnable jar in target/
java -jar target/demo-0.0.1-SNAPSHOT.jar   # run the built jar
```

> Add **spring-boot-devtools** as a dependency for automatic restart on code changes —
> a big quality-of-life boost while learning.

---

## 12. Common Pitfalls

| Symptom | Likely cause |
|--------|--------------|
| Endpoint returns **404** | Controller is outside the base package `com.example.demo`, or the path/HTTP method is wrong. |
| `NoSuchBeanDefinitionException` | Class isn't annotated (`@Service` etc.) or is in an unscanned package. |
| JSON body is all `null` | Missing no-arg constructor or getters/setters on the model. |
| `Field injection is not recommended` warning | Use constructor injection instead of `@Autowired` on a field. |
| Two beans of same type → startup fails | Add `@Qualifier` to pick one, or mark one `@Primary`. |
| DB table not created | `spring.jpa.hibernate.ddl-auto` not set, or class missing `@Entity`. |
| Port already in use | Another app on 8080 — change `server.port` or stop the other process. |

---

## 13. Naming & Style Conventions

- **Packages:** lowercase, singular: `controller`, `service`, `repository`, `model`.
- **Classes:** suffix by role: `UserController`, `UserService`, `UserRepository`.
- **Constructor injection** with `private final` fields — no field `@Autowired`.
- **One responsibility per layer** — no DB calls in controllers, no HTTP in services.
- **DTOs for the API** — don't expose `@Entity` classes directly in requests/responses
  once the project grows; map to `dto/` classes.
- **Return proper HTTP status codes** — `201` for create, `404` for not found, etc.

---

## Where to Go Next

- Add **Spring Data JPA + H2** (section 8) and rebuild the User example with a real DB.
- Add **spring-boot-starter-actuator** for health/metrics endpoints (`/actuator/health`).
- Add **spring-boot-starter-security** when you need auth.
- Official guides: https://spring.io/guides — short, task-focused, excellent for beginners.
- Reference docs: https://docs.spring.io/spring-boot/index.html

> Tip: whenever you want current, version-accurate Spring Boot docs, just ask me — I can
> pull the latest official documentation for the exact feature you're working on.

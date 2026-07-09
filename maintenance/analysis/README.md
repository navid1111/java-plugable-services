# Lab Analysis Report — auth-service

**Course:** CSE 4802 · **Codebase analyzed:** `auth-service` (Spring Boot, Java 21)
**Labs applied:** Lab 1 (Static & Dynamic Analysis) · Lab 3 (Transformation Tools)
**Date:** 2026-07-10

The lab demos use Python tools; this project is Java, so each lab concept is applied
with its Java equivalent (same techniques, same outputs).

---

## 1. Static Analysis (Lab 1)

> *Static analysis: analyzing program code without executing it.*

### 1.1 Generate AST

**Tool:** `javalang` (Python library that parses Java source into an Abstract Syntax
Tree — same role as Python's `ast` module in the lab slides).

Script: `dump_ast.py` parses the source and prints every AST node with indentation
showing the tree structure (node type + identifying attribute).

Outputs:
- [`ast-AuthController.txt`](ast-AuthController.txt) — 233 nodes
- [`ast-JwtService.txt`](ast-JwtService.txt) — 108 nodes

Reading the AST confirms the structure comprehension of the service, e.g. for
`AuthController`:

```
CompilationUnit
└── ClassDeclaration (name='AuthController')
    ├── Annotation (name='RestController')
    ├── Annotation (name='RequestMapping')  → Literal '"/auth"'
    ├── FieldDeclaration → UserRepository users
    ├── FieldDeclaration → PasswordEncoder passwordEncoder
    ├── FieldDeclaration → JwtService jwtService
    ├── ConstructorDeclaration (dependency injection of the 3 fields)
    ├── ClassDeclaration (name='Credentials')     ← request payload record
    ├── MethodDeclaration (name='register')  @PostMapping
    ├── MethodDeclaration (name='login')     @PostMapping
    └── MethodDeclaration (name='me')        @GetMapping
```

### 1.2 Metrics Calculation

**Tool:** `lizard` (multi-language code metrics: NLOC, cyclomatic complexity CCN,
token count, parameter count — the metrics SonarQube reports in the lab slides).

Output: [`metrics-lizard.txt`](metrics-lizard.txt)

Summary (whole service):

| Metric | Value |
|---|---|
| Total NLOC | 200 |
| Functions | 17 |
| Average CCN | 1.5 |
| Max CCN | **6 — `AuthController.register()`** |
| Threshold violations (CCN > 15) | 0 |

**Finding that feeds the maintenance project:** `register()` (CCN 6) and `me()` (CCN 4)
are the most complex methods in the service. `register()` is exactly where the
corrective-maintenance bug lives (untrimmed username, CR-001) — the static metrics
independently point at the same hotspot.

---

## 2. Dynamic Analysis (Lab 1)

> *Dynamic analysis: tracking what happens during program execution.*

### 2.1 Code Coverage

**Tool:** JaCoCo (Java's standard coverage tool — same role as `coverage.py`).
Added to `auth-service/pom.xml`; an H2 in-memory database (test scope) lets tests run
without Postgres.

Run: `./mvnw test` → report at `auth-service/target/site/jacoco/index.html`

**Baseline result** (only test = the default `contextLoads`) — [`coverage-baseline.csv`](coverage-baseline.csv):

| Class | Instructions covered | Branches covered |
|---|---|---|
| AuthController | 12 / 179 (7%) | **0 / 14 (0%)** |
| JwtService | 15 / 60 (25%) | – |
| User | 3 / 25 (12%) | – |
| SecurityConfig | 7 / 7 (100%) | – |
| **Total** | **43 / 295 (14.6%)** | **0 / 14 (0%)** |

The little coverage that exists comes only from Spring instantiating the beans during
context startup — **no behavior is tested at all** (0% branch coverage). This measured
baseline is the justification for CR-004 (preventive maintenance: add unit/web tests);
re-running `./mvnw test` after CR-004 produces the before/after comparison.

### 2.2 Execution Tracing

**Tool:** Spring Boot DEBUG logging (Logback) while driving the running service with
`curl` — the Java analog of the lab's `sys.settrace` / PySnooper line tracing.

The service was started locally (H2 in-memory DB, port 8085) with
`--logging.level.org.springframework.web=DEBUG` and driven with `curl`.

Evidence files:
- [`curl-session.txt`](curl-session.txt) — full request/response session
- [`trace-debug-log.txt`](trace-debug-log.txt) — the framework's execution trace, e.g.:

```
DispatcherServlet : POST "/auth/register", parameters={}
RequestMappingHandlerMapping : Mapped to AuthController#register(Credentials)
DispatcherServlet : Completed 201 CREATED
DispatcherServlet : POST "/auth/login", parameters={}
RequestMappingHandlerMapping : Mapped to AuthController#login(Credentials)
DispatcherServlet : Completed 200 OK
```

**Dynamic analysis found the corrective-maintenance bug live (CR-001):**

| Step | Request | Expected | Actual |
|---|---|---|---|
| 1 | register `"bob"` → login → `/auth/me` | works | ✅ works (happy path) |
| 2 | register `"  alice  "` (surrounding spaces) | reject or normalize | ❌ **201, stored with spaces** |
| 3 | login `"alice"` with the same password | 200 + token | ❌ **401 invalid credentials** |
| 4 | register `"alice"` again | 409 conflict | ❌ **201 — duplicate logical account** |

Static analysis pointed at `register()` (highest CCN); dynamic analysis confirms the
fault there at runtime. This is the reproduction record required by CR-001.

---

## 3. Transformation Tools (Lab 3)

> *A transformation tool converts programs between representations (text ⇄ visual),
> and observation tools (logging/tracing/profiling) make execution comprehensible.*

| Lab 3 tool | Purpose in lab | Java equivalent used here |
|---|---|---|
| Loguru | structured, leveled logging | SLF4J/Logback (built into Spring Boot) — see traced flow above |
| PySnooper | line-by-line execution trace | Spring DEBUG/TRACE logging of the request flow |
| cProfile / SnakeViz / VizTracer | profiling + visualization | JDK Flight Recorder / VisualVM (optional; requires running JVM) |
| code → visual | AST Explorer | AST dumps in §1.1 (code transformed to tree representation) |

---

## 4. How this feeds the maintenance project

- Static metrics identified `register()` as the complexity hotspot → where CR-001 (corrective) is fixed.
- Coverage baseline is the quantitative justification for CR-004 (preventive: add tests);
  re-running JaCoCo after CR-004 gives the before/after evidence.
- The traced login/register flow is the program-comprehension record for Phase 1.

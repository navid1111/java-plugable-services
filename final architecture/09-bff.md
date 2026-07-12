# bff — Architecture (Backend-for-Frontend / API composer)

Owns the `/bff` prefix: **client-shaped read composition** across owning services. It holds
**no database and no source of truth** — it fans out to tweeter / comment / media, stitches
their responses into one client-friendly payload, and enforces **strict deadlines with
partial (degraded) responses**.

## Component / request flow

```mermaid
graph TB
    Client([Client]) -->|"GET /bff/feed<br/>GET /bff/posts/{id}"| Kong["Kong Gateway (/bff = JWT)"]

    Kong --> Tomcat

    subgraph BFF["bff (:8080) — stateless composer"]
        Tomcat["DispatcherServlet"]

        subgraph Web["Controllers"]
            FC["FeedController /bff/feed"]
            PDC["PostDetailController /bff/posts/{id}"]
            EH["CompositionProblemHandler<br/>maps 404/410/critical failures"]
        end

        subgraph Comp["Composers (bounded-parallel, one deadline)"]
            FComp["FeedComposer<br/>authoritative page + optional fan-out"]
            PComp["PostDetailComposer<br/>critical owner + optional sections"]
            DV["DownstreamViews<br/>typed client to each service"]
        end

        Cfg["DownstreamConfig / BffProperties<br/>base URLs + composition executor +<br/>deadline"]

        Tomcat --> FC --> FComp
        Tomcat --> PDC --> PComp
        FComp --> DV
        PComp --> DV
        FComp -. "throws" .-> EH
        PComp -. "throws" .-> EH
    end

    DV ==>|"GET posts / feed (CRITICAL)"| Tweeter([tweeter-service])
    DV ==>|"GET comment summary (optional)"| Comment([comment-service])
    DV ==>|"GET media summary (optional)"| Media([media-service])
```

## Composition semantics

```mermaid
graph LR
    R["/bff/posts/{id}"] --> Owner{"tweeter (owner)<br/>CRITICAL"}
    Owner -->|404| G404["404 Not Found"]
    Owner -->|410 gone| G410["410 Gone (PostGone)"]
    Owner -->|ok| Par["fan out in parallel<br/>within remaining deadline"]
    Par --> Cm["comments (optional)"]
    Par --> Md["media (optional)"]
    Cm -->|slow/failing| Deg["omit section →<br/>list in 'degraded'"]
    Md -->|slow/failing| Deg
    Cm -->|ok| Full
    Md -->|ok| Full["composed response"]
    Deg --> Full
```

## Responsibilities & contracts

- **`GET /bff/feed`** — fetch the authoritative cursor page from tweeter once, then fan out optional comment/media summaries on a bounded executor sharing **one page deadline** (avoids N serial timeouts multiplying latency).
- **`GET /bff/posts/{id}`** — the **owner (tweeter) is the critical dependency**: its 404 → 404, its 410 → `PostGone`. Comments and media are **optional**, fetched in bounded parallel; if slow or failing, the section is omitted and named in `degraded` rather than failing the whole response.
- **Error mapping** — `CompositionProblemHandler` translates `PostNotFoundException` / `PostGoneException` / `CriticalDependencyException` into proper HTTP status.

## Notable design choices

- **No DB, no ownership** — the BFF never persists or owns data; it strictly composes reads from owning services, preserving database-per-service boundaries.
- **Critical vs optional dependencies** — an explicit contract: only the owning service can fail the request; enrichment degrades gracefully.
- **One shared deadline + bounded parallelism** — every optional call shares a single budget on a bounded executor, so one slow dependency can't multiply tail latency or exhaust threads.
- **Partial responses over hard failure** — clients get the best available view plus an honest `degraded` list, instead of an all-or-nothing 500.

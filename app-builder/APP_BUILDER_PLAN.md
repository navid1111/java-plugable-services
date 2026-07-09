# App Builder: Hermes Gateway + Pluggable Java Backends

## Target

Build a local app-generation service in this repository, not VoltEdge.

The system should feel like Lovable/v0 on the frontend side, but backend generation is constrained by the Java plug kits already present in this repo:

- `auth-service/plug`
- `tweeter-service/plug`
- `comment-service/plug`
- `post-search-service/plug`
- `media-service/plug`
- other future `*/plug` service kits

VoltEdge is only a reference pattern: it used Claude as the agent. This project should use Hermes as the agent/gateway/orchestrator.

## Current first slice implemented

`app-builder` is a new Spring Boot module that scans `/home/navid/java` for service plug kits and exposes the discovered backend capabilities.

Implemented contract:

- A service is discovered when it has a `plug/` directory.
- A service is `AVAILABLE` when it contains:
  - `plug/compose.plug.yml`
  - `plug/kong-setup.sh`
  - `plug/smoke.sh`
- Otherwise it is `DEVELOPING`.
- Kong gateway paths are extracted from `kong-setup.sh` route path declarations.
- Natural-language requests are assessed against known plug services and unknown capabilities are returned as developing/missing.

HTTP endpoint implemented:

```text
GET /api/plugs
```

This gives Hermes and the generated frontend a grounded source of truth before promising a backend capability.

## Intended runtime architecture

```text
User prompt
  -> app-builder chat/generate API
  -> Hermes gateway/orchestrator
  -> plug catalog lookup
  -> capability assessment
  -> generated app workspace
       -> frontend like Lovable/v0
       -> backend wiring only from available Java plug kits
       -> clear unavailable/developing markers for missing services
  -> run/smoke via Kong
```

## Generation rules

1. Always query the plug catalog before generating backend behavior.
2. If a requested capability maps to an `AVAILABLE` plug service:
   - include that service's compose fragment,
   - register its Kong paths,
   - generate UI that calls the gateway path,
   - include the plug smoke test in verification.
3. If a requested capability does not map to an available plug service:
   - do not hallucinate a working backend,
   - mark it as `DEVELOPING`,
   - generate a disabled or placeholder UI state,
   - tell the user exactly which service needs to be built.
4. Generated frontend can be flexible like Lovable/v0, but backend capabilities must stay registry-backed.
5. Hermes is the orchestrator; Java services remain the pluggable runtime backend.

## Next implementation slices

### Slice 2: capability assessment endpoint

Add:

```text
POST /api/assess
body: { "prompt": "make me a login and booking app" }
```

Response:

```json
{
  "availableServiceIds": ["auth-service", "booking-service"],
  "developingCapabilities": ["payments"]
}
```

### Slice 3: generated app manifest

Add a generator that writes a manifest, not arbitrary code first:

```json
{
  "appName": "...",
  "prompt": "...",
  "services": [
    {
      "id": "auth-service",
      "gatewayPaths": ["/auth"],
      "status": "AVAILABLE"
    }
  ],
  "missing": ["payments"],
  "frontend": {
    "pages": [],
    "components": [],
    "apiBindings": []
  }
}
```

This manifest becomes the contract between Hermes, generated UI, and Java backend plugs.

### Slice 4: workspace scaffold

Create a workspace per generated app:

```text
.generated-apps/<slug>/
  app.manifest.json
  docker-compose.generated.yml
  frontend/
  README.md
```

The generated compose file should merge selected `*/plug/compose.plug.yml` fragments rather than inventing services.

### Slice 5: Lovable/v0-style frontend

Start with a React/Vite generated frontend that reads `app.manifest.json` and renders:

- polished page shell,
- generated forms/tables/cards per capability,
- real gateway calls for `AVAILABLE` services,
- disabled cards and "being developed" notices for missing capabilities.

### Slice 6: Hermes integration

Expose an app-builder prompt endpoint that calls Hermes with:

- user prompt,
- plug catalog JSON,
- generation rules,
- workspace path,
- required output schema.

Hermes should be allowed to design frontend UX and write workspace files, but not to claim unavailable Java services are working.

## Verification gates

For every generated app:

1. `mvn test` for app-builder.
2. Start selected Docker Compose profiles.
3. Run each selected service's `plug/smoke.sh`.
4. Verify frontend can call Kong paths.
5. Confirm missing capabilities are displayed as developing, not silently mocked as real backend behavior.

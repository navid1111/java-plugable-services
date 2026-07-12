"""Reads the Java plug catalog and turns it into a `plugs` skill the agent reads.

The skill is the grounding: it lists which backends exist, their real HTTP
endpoints (scanned from the Java controllers), and how to call them through Kong.
The agent may only wire calls to endpoints listed here.
"""

import logging

import httpx

from .config import settings

logger = logging.getLogger("appbuilder.catalog")


async def fetch_catalog() -> tuple[list[dict], dict[str, list[str]]]:
    """Return (plugs, endpoints_by_service) from the running Java catalog."""
    async with httpx.AsyncClient(timeout=10.0) as client:
        plugs_resp = await client.get(f"{settings.catalog_url}/api/plugs")
        plugs_resp.raise_for_status()
        plugs = plugs_resp.json()

        endpoints: dict[str, list[str]] = {}
        try:
            ep_resp = await client.get(f"{settings.catalog_url}/api/plugs/endpoints")
            ep_resp.raise_for_status()
            endpoints = ep_resp.json()
        except httpx.HTTPError:
            logger.warning("could not fetch /api/plugs/endpoints; skill will list gateway paths only")
        return plugs, endpoints


def render_skill(plugs: list[dict], endpoints: dict[str, list[str]]) -> str:
    """Build the SKILL.md the agent consults before wiring any backend call."""
    available = [p for p in plugs if p.get("status") == "AVAILABLE"]
    developing = [p for p in plugs if p.get("status") != "AVAILABLE"]

    lines: list[str] = [
        "---",
        "name: plugs",
        "description: The pluggable Java backend services available to this app, "
        "their real HTTP endpoints, and how to call them through the Kong gateway. "
        "Read this before wiring any backend call.",
        "---",
        "",
        "# Available backend plugs",
        "",
        f"All backend calls go through the Kong gateway at `{settings.gateway_url}`.",
        "In generated JS, define `const GATEWAY = \"" + settings.gateway_url + "\";` and build every"
        " fetch() URL from it, e.g. `fetch(GATEWAY + \"/posts/feed\")`.",
        "",
        "**Rules**",
        "- Only call endpoints listed below. Never invent an endpoint or a service.",
        "- Treat the gateway URL as the runtime backend. If a request to it fails, show a visible"
        " backend/gateway error instead of silently falling back to mock data.",
        "- If a feature the user asked for has no matching backend, render a visible but"
        " disabled placeholder labelled 'Being developed — backend not available yet'. Do not fake it.",
        "",
    ]

    if available:
        lines.append("## Ready to wire (AVAILABLE)")
        lines.append("")
        for plug in available:
            eps = endpoints.get(plug["id"], [])
            gateways = ", ".join(plug.get("gatewayPaths", [])) or "(none)"
            lines.append(f"### {plug.get('displayName', plug['id'])}  (`{plug['id']}`)")
            lines.append(f"- Gateway path(s): {gateways}")
            if eps:
                lines.append("- Endpoints (call as `GATEWAY + <path>`):")
                for ep in eps:
                    lines.append(f"  - `{ep}`")
            lines.append("")
    else:
        lines.append("## Ready to wire (AVAILABLE)\n\n_None discovered._\n")

    if developing:
        lines.append("## Not ready (DEVELOPING — placeholder only)")
        lines.append("")
        for plug in developing:
            lines.append(f"- `{plug['id']}` — no complete plug kit yet; render a disabled placeholder.")
        lines.append("")

    lines += [
        "## Composition pattern",
        "",
        "Content items are **tweeter posts** (`/posts`). Attach media and comments to any"
        " item with the shared `targets/{targetType}/{targetId}` pattern:",
        "- A post = `POST /posts`; feed = `GET /posts/feed`; one = `GET /posts/{id}`.",
        "- Media (image/video, Cloudinary-backed) for a post = multipart `POST /media/targets/post/{postId}`"
        " (form field `file`); list = `GET /media/targets/post/{postId}`.",
        "- Comments for a post = `POST` / `GET /comments/targets/post/{postId}`.",
        "- Post search requires a query string: `GET /post-search?q=<term>`; do not call",
        " `/post-search` without `q`.",
        "- Index a post for search with `PUT /post-search/documents/post/{postId}` and JSON",
        " `{\"authorUsername\": string, \"content\": string, \"createdAt\": ISO-8601 timestamp}`.",
        "  The target type/id are path parameters, not request-body fields.",
        "- Update indexed like counts with `PUT /post-search/documents/post/{postId}/like-count`",
        " and JSON `{\"likeCount\": number}`.",
        "",
        "Reuse this pattern to compose apps. For example, a YouTube-style app should compose:",
        "- posts/feed for video records and metadata,",
        "- media for upload/playback attachments on each post,",
        "- comments for discussion on each video post, and",
        "- post-search for finding videos when that plug is AVAILABLE.",
        "",
    ]

    if "bff" in {p["id"] for p in available}:
        lines += [
            "## Prefer the BFF for composite reads",
            "",
            "The **BFF** (`/bff`) composes reads across tweeter + comments + media on the"
            " server, with a strict deadline and graceful partial responses. When it is"
            " AVAILABLE, use it for read screens instead of fanning out to each service and"
            " stitching on the client — one call replaces several and handles slow/failing"
            " optional sections for you.",
            "",
            "Rule of thumb — **reads through the BFF, writes direct**:",
            "- Feed / post-detail *reads* → call `/bff/*` (below).",
            "- Everything else (create post, follow, comment, upload media, search) → call the"
            " owning service directly. The BFF is read-only; it has no write endpoints.",
            "",
            "Only these two BFF endpoints exist — never invent others:",
            "- `GET /bff/feed` → `{ items: PostDetail[], nextCursor: string|null,"
            " sourceVersionWatermark: number }`",
            "- `GET /bff/posts/{id}` → a single `PostDetail`.",
            "",
            "`PostDetail` shape (render defensively — `comments`/`media` may be `null`):",
            "```json",
            "{",
            "  \"post\":     { \"id\": number, \"content\": string, \"createdAt\": string,"
            " \"updatedAt\": string, \"version\": number },",
            "  \"author\":   { \"username\": string },",
            "  \"comments\": { \"commentCount\": number } | null,",
            "  \"media\":    { \"mediaCount\": number } | null,",
            "  \"degraded\": string[]",
            "}",
            "```",
            "- `comments` or `media` is `null` when that optional section could not be composed"
            " in time; its name then appears in `degraded`. Show the rest of the post normally",
            "  and treat a listed section as 'temporarily unavailable', not an error.",
            "- A `404` from `/bff/posts/{id}` means the post does not exist (or was deleted) —"
            " the BFF only fails the whole read when the owning post itself is missing.",
            "",
        ]

    return "\n".join(lines)


def _endpoint_lines(plugs: list[dict], endpoints: dict[str, list[str]]) -> list[str]:
    lines: list[str] = []
    for plug in plugs:
        if plug.get("status") != "AVAILABLE":
            continue
        eps = endpoints.get(plug["id"], [])
        lines.append(f"- {plug.get('displayName', plug['id'])} (`{plug['id']}`):")
        if eps:
            for ep in eps:
                lines.append(f"  - `{ep}`")
        else:
            gateways = ", ".join(plug.get("gatewayPaths", [])) or "(none discovered)"
            lines.append(f"  - Gateway path(s): {gateways}")
    return lines or ["- No AVAILABLE endpoints discovered."]


def render_agents_md(plugs: list[dict], endpoints: dict[str, list[str]]) -> str:
    """Build AGENTS.md for CLIs that auto-read root context, especially Codex."""
    lines: list[str] = [
        "# App-builder workspace instructions",
        "",
        "Build only the static frontend files in this directory. `index.html` is the entry point.",
        "",
        "## Backend ground truth",
        "",
        f"- Gateway base URL: `{settings.gateway_url}`",
        "- Read `.hermes/skills/plugs/SKILL.md` before wiring backend calls.",
        "- Only call endpoints listed below or in that skill. Never invent services or paths.",
        "- Browser requests are cross-origin; Kong must answer CORS preflight. If CORS fails, show a real error.",
        "- Content services require JWT auth. Do not call `/posts`, `/media`, `/comments`, or `/post-search` anonymously.",
        "",
        "## Required auth flow",
        "",
        "```js",
        "const GATEWAY = \"" + settings.gateway_url + "\";",
        "async function api(path, options = {}) {",
        "  const headers = { ...(options.headers || {}) };",
        "  if (!(options.body instanceof FormData)) headers['Content-Type'] = headers['Content-Type'] || 'application/json';",
        "  const token = localStorage.getItem('appbuilder.jwt');",
        "  if (token) headers.Authorization = `Bearer ${token}`;",
        "  const res = await fetch(GATEWAY + path, { ...options, headers });",
        "  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);",
        "  return res.status === 204 ? null : res.json();",
        "}",
        "async function login(username, password) {",
        "  const res = await fetch(GATEWAY + '/auth/login', {",
        "    method: 'POST',",
        "    headers: { 'Content-Type': 'application/json' },",
        "    body: JSON.stringify({ username, password })",
        "  });",
        "  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);",
        "  const data = await res.json();",
        "  localStorage.setItem('appbuilder.jwt', data.access_token);",
        "}",
        "```",
        "",
        "## Real endpoint inventory from `/api/plugs/endpoints`",
        "",
        *_endpoint_lines(plugs, endpoints),
        "",
        "## Required feedback loop",
        "",
        "After writing or changing backend fetch code, run `./verify-backend.sh` from this directory.",
        "Do not claim the backend is wired until it passes. If it fails, fix the frontend contract or report the exact blocker.",
        "",
    ]
    return "\n".join(lines)


def render_backend_verifier() -> str:
    """Shell smoke test agents can run from a workspace after wiring fetch()."""
    return f'''#!/usr/bin/env bash
set -euo pipefail

BASE="${{GATEWAY_URL:-{settings.gateway_url}}}"
ORIGIN="${{APPBUILDER_ORIGIN:-http://localhost:8090}}"
STAMP="$(date +%s)"
USER="appbuilder_${{STAMP}}"
PASS="appbuilder_pass"

echo "[verify] Gateway: $BASE"

echo "[verify] CORS preflight"
PREFLIGHT="$(curl -isS -X OPTIONS "$BASE/posts/feed" \
  -H "Origin: $ORIGIN" \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: Authorization,Content-Type')"
printf '%s' "$PREFLIGHT" | grep -qi '^access-control-allow-origin:' || {{
  echo "CORS preflight did not return Access-Control-Allow-Origin"
  printf '%s\n' "$PREFLIGHT"
  exit 1
}}

echo "[verify] Anonymous content call is rejected by JWT"
CODE="$(curl -sS -o /dev/null -w '%{{http_code}}' "$BASE/posts/feed")"
test "$CODE" = "401" || {{ echo "Expected 401 from anonymous /posts/feed, got $CODE"; exit 1; }}

echo "[verify] Register and login"
AUTH_BODY="$(printf '{{"username":"%s","password":"%s"}}' "$USER" "$PASS")"
curl -fsS -X POST "$BASE/auth/register" -H 'Content-Type: application/json' -d "$AUTH_BODY" >/dev/null
LOGIN="$(curl -fsS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d "$AUTH_BODY")"
TOKEN="$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))')"
test -n "$TOKEN" || {{ echo "Login did not return access_token: $LOGIN"; exit 1; }}

echo "[verify] Authenticated content call"
curl -fsS "$BASE/posts/feed" -H "Authorization: Bearer $TOKEN" >/dev/null

echo "[verify] Create post"
POST="$(curl -fsS -X POST "$BASE/posts" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{{"content":"app-builder verify %s"}}' "$STAMP")")"
POST_ID="$(printf '%s' "$POST" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')"
test -n "$POST_ID" || {{ echo "Could not parse post id: $POST"; exit 1; }}

echo "[verify] Backend smoke passed: auth + JWT + CORS + /posts via Kong"
'''

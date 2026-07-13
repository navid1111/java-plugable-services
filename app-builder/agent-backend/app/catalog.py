"""Reads the Java plug catalog and turns it into a `plugs` skill the agent reads.

The skill is the grounding: it lists which backends exist, their real HTTP
endpoints (scanned from the Java controllers), and how to call them through Kong.
The agent may only wire calls to endpoints listed here.
"""

import logging

import httpx

from .config import settings

logger = logging.getLogger("appbuilder.catalog")


def _browser_endpoints(values: list[str]) -> list[str]:
    """Defence in depth: never advertise workload-only routes to a browser agent."""
    return [endpoint for endpoint in values if " /internal" not in endpoint]


def render_frontend_contract_verifier() -> str:
    """Standalone stdlib-only linter copied into every generated app workspace."""
    return r'''#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path


def source_files(root: Path) -> list[Path]:
    candidates = {*root.rglob("*.html"), *root.rglob("*.js")}
    return sorted(
        path for path in candidates
        if not any(part.startswith(".") for part in path.relative_to(root).parts)
    )


def nearby_method(source: str, marker: str, method: str) -> bool:
    for match in re.finditer(re.escape(marker), source, re.IGNORECASE):
        window = source[max(0, match.start() - 250):match.end() + 500]
        if re.search(r"method\s*:\s*['\"]" + re.escape(method) + r"['\"]", window, re.IGNORECASE):
            return True
    return False


def lint(source: str) -> list[str]:
    problems: list[str] = []
    lowered = source.lower()

    if re.search(r"['\"]/internal(?:/|['\"])", source, re.IGNORECASE):
        problems.append("browser code must not call /internal/* workload APIs")

    if nearby_method(source, "/auth/me", "DELETE") and "appbuilder: allow-account-deactivation" not in lowered:
        problems.append("DELETE /auth/me deactivates the account; logout must only remove the local JWT")

    if nearby_method(source, "/post-search/documents/", "PUT"):
        problems.append("public search mutations do not exist; search indexing comes from post lifecycle events")

    if "postWithFallback" in source:
        problems.append("do not guess multiple write payloads; use the single documented request contract")

    if re.search(
        r"['\"]Content-Type['\"]\s*\]?\s*[:=]\s*['\"]multipart/form-data",
        source,
        re.IGNORECASE,
    ):
        problems.append("do not set multipart/form-data manually when sending FormData; the browser adds its boundary")

    protected = any(path in source for path in ("/posts", "/media", "/comments", "/post-search", "/bff"))
    if protected:
        for token, message in (
            ("appbuilder.jwt", "protected apps must load the JWT from appbuilder.jwt"),
            ("Authorization", "protected requests must send the Authorization header"),
            ("Bearer", "protected requests must use the Bearer token scheme"),
        ):
            if token not in source:
                problems.append(message)

    uses_media_intents = "/media/upload-intents" in source
    if uses_media_intents:
        required = {
            "targetType": "upload intent body requires targetType",
            "targetId": "upload intent body requires targetId",
            "idempotencyKey": "upload intent body requires a unique idempotencyKey",
            "resourceType": "upload intent body requires resourceType",
            "format": "upload intent body requires an extension-only format",
            "bytes": "upload intent body requires bytes",
            "authorization": "upload intent response is nested under authorization",
            "uploadUrl": "direct upload must use authorization.uploadUrl",
            "api_key": "Cloudinary form requires api_key",
            "signature": "Cloudinary form requires signature",
            "public_id": "Cloudinary form requires public_id",
            "publicId": "finalize body requires publicId from the provider response",
            "secureUrl": "finalize body requires secureUrl from the provider response",
            "reasonCode": "failed intents must send reasonCode",
        }
        for token, message in required.items():
            if token not in source:
                problems.append(message)

    creates_posts = bool(re.search(r"['\"]/posts['\"]", source))
    writes_media = uses_media_intents or nearby_method(source, "/media/targets/", "POST")
    has_summary = "/media/targets/" in source and "/summary" in source
    has_backoff = any(token in source for token in ("setTimeout", "sleep(", "pause("))
    handles_projection_race = "target does not exist or is deleted" in lowered
    if creates_posts and writes_media and not (has_summary and has_backoff and handles_projection_race):
        problems.append(
            "post -> media writes require bounded target-projection readiness retry for "
            "'target does not exist or is deleted'"
        )

    if nearby_method(source, "/follow", "PUT") and "username=" not in source:
        problems.append("follow PUT requires ?username={author.username} and the BFF author.userId")

    return problems


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    files = source_files(root)
    if not files:
        print("[contract-lint] no HTML/JS source files found", file=sys.stderr)
        return 1
    source = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in files)
    problems = lint(source)
    if problems:
        print("[contract-lint] frontend/backend contract violations:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"[contract-lint] passed ({len(files)} source file(s))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
'''


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
        "- Never call `/internal/*` from a browser. Internal endpoints require workload identity",
        " and are deliberately not exposed through the public Kong gateway.",
        "- Logout is client-side: remove `appbuilder.jwt` from local storage. `DELETE /auth/me`",
        " permanently deactivates the account and must never be used as logout.",
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
            eps = _browser_endpoints(endpoints.get(plug["id"], []))
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
        "- Target ownership is projected asynchronously from post lifecycle events. Immediately",
        " after `POST /posts`, wait/retry the media or comment target summary with bounded backoff",
        " when it returns `target does not exist or is deleted`; do not retry unrelated 4xx errors.",
        "- Post search requires a query string: `GET /post-search?q=<term>`; do not call",
        " `/post-search` without `q`.",
        "- Search indexing is asynchronous. Creating/updating/deleting a post emits an event and",
        " `post-search-service` updates its projection. Do not call a public search mutation; none exists.",
        "- Follow writes require the BFF author's public id:",
        " `PUT /posts/users/{author.userId}/follow?username={author.username}`; use `DELETE`",
        " on the same path to unfollow.",
        "",
        "### Write contracts (exact JSON shapes)",
        "",
        "- Create a post: `POST /posts` with `{\"content\": string}` (maximum 280 characters).",
        "- Create a comment: `POST /comments/targets/post/{postId}` with `{\"content\": string}`.",
        "- The simplest media write is multipart `POST /media/targets/post/{postId}` with a",
        " `file` field. Do not set `Content-Type` manually when sending `FormData`.",
        "- For direct-to-Cloudinary uploads, create an intent with exactly:",
        "```json",
        "{\"targetType\":\"post\",\"targetId\":\"123\",\"idempotencyKey\":\"unique-key\",",
        " \"resourceType\":\"image|video\",\"format\":\"png|jpg|webp|mp4|mov|webm\",\"bytes\":1234}",
        "```",
        "  `POST /media/upload-intents` returns `{ intent, authorization }`. Upload a multipart",
        "  form to `authorization.uploadUrl` containing `file`, `api_key`, `timestamp`, `signature`,",
        "  `public_id`, and `folder` (when non-empty). Then finalize using the Cloudinary response:",
        "```json",
        "{\"publicId\":string,\"resourceType\":string,\"format\":string,\"secureUrl\":string,",
        " \"bytes\":number,\"width\":number|null,\"height\":number|null,",
        " \"durationSeconds\":number|null,\"originalFilename\":string}",
        "```",
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
            "  \"author\":   { \"userId\": string, \"username\": string },",
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
        eps = _browser_endpoints(endpoints.get(plug["id"], []))
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
        "- Never call `/internal/*` from browser code; those routes require service workload identity.",
        "- Logout only removes the local JWT. `DELETE /auth/me` permanently deactivates the account.",
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
        "`./verify-backend.sh` first lints the actual HTML/JS and then exercises the live stack.",
        "Do not edit, delete, weaken, or bypass `verify-frontend-contracts.py` or the verifier.",
        "After writing or changing backend fetch code, run `./verify-backend.sh` from this directory.",
        "Do not claim the backend is wired until it passes. If it fails, fix the frontend contract or report the exact blocker.",
        "",
    ]
    return "\n".join(lines)


def render_backend_verifier() -> str:
    """End-to-end gateway test for the service composition used by generated apps."""
    return f'''#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${{BASH_SOURCE[0]}}")" && pwd)"
cd "$ROOT"

BASE="${{GATEWAY_URL:-{settings.gateway_url}}}"
ORIGIN="${{APPBUILDER_ORIGIN:-http://localhost:8090}}"
STAMP="$(date +%s%N)"
USER="appbuilder_${{STAMP}}"
PASS="appbuilder_pass"
AUTH_HEADER=()

json_field() {{
  python3 -c 'import json,sys
value=json.load(sys.stdin)
for part in sys.argv[1].split("."):
    value=value.get(part) if isinstance(value,dict) else None
print("" if value is None else value)' "$1"
}}

echo "[verify] Gateway: $BASE"

echo "[verify] Generated frontend contract lint"
python3 ./verify-frontend-contracts.py .

echo "[verify] CORS preflight for an authenticated media write"
PREFLIGHT="$(curl -isS -X OPTIONS "$BASE/media/upload-intents" \
  -H "Origin: $ORIGIN" \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Authorization,Content-Type,Idempotency-Key')"
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
TOKEN="$(printf '%s' "$LOGIN" | json_field access_token)"
test -n "$TOKEN" || {{ echo "Login did not return access_token: $LOGIN"; exit 1; }}
AUTH_HEADER=(-H "Authorization: Bearer $TOKEN")

echo "[verify] Authenticated identity"
ME="$(curl -fsS "$BASE/auth/me" "${{AUTH_HEADER[@]}}")"
USER_ID="$(printf '%s' "$ME" | json_field userId)"
test -n "$USER_ID" || {{ echo "GET /auth/me did not return userId: $ME"; exit 1; }}

echo "[verify] Create post"
CONTENT="appbuilderverify${{STAMP}}"
POST="$(curl -fsS -X POST "$BASE/posts" \
  "${{AUTH_HEADER[@]}}" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{{"content":"%s"}}' "$CONTENT")")"
POST_ID="$(printf '%s' "$POST" | json_field id)"
test -n "$POST_ID" || {{ echo "Could not parse post id: $POST"; exit 1; }}

echo "[verify] BFF feed and detail composition"
BFF_DETAIL="$(curl -fsS "$BASE/bff/posts/$POST_ID" "${{AUTH_HEADER[@]}}")"
printf '%s' "$BFF_DETAIL" | python3 -c 'import json,sys
d=json.load(sys.stdin)
expected_id,expected_user=sys.argv[1],sys.argv[2]
assert str(d.get("post",{{}}).get("id")) == expected_id, d
assert d.get("author",{{}}).get("userId") == expected_user, d' "$POST_ID" "$USER_ID"
curl -fsS "$BASE/bff/feed" "${{AUTH_HEADER[@]}}" >/dev/null

echo "[verify] Event-driven target projections"
MEDIA_READY=0
for _ in 1 2 3 4 5 6; do
  CODE="$(curl -sS -o /dev/null -w '%{{http_code}}' \
    "$BASE/media/targets/post/$POST_ID/summary" "${{AUTH_HEADER[@]}}")"
  if [ "$CODE" = "200" ]; then MEDIA_READY=1; break; fi
  sleep 0.25
done
test "$MEDIA_READY" = "1" || {{ echo "Media target projection did not become ready"; exit 1; }}

echo "[verify] Comment write"
COMMENT="$(curl -fsS -X POST "$BASE/comments/targets/post/$POST_ID" \
  "${{AUTH_HEADER[@]}}" -H 'Content-Type: application/json' \
  -d '{{"content":"app builder backend verification"}}')"
test -n "$(printf '%s' "$COMMENT" | json_field id)" || {{ echo "Comment response missing id: $COMMENT"; exit 1; }}

echo "[verify] Media upload-intent contract"
INTENT_BODY="$(printf '{{"targetType":"post","targetId":"%s","idempotencyKey":"verify-%s","resourceType":"image","format":"png","bytes":68}}' "$POST_ID" "$STAMP")"
INTENT="$(curl -fsS -X POST "$BASE/media/upload-intents" \
  "${{AUTH_HEADER[@]}}" -H 'Content-Type: application/json' -d "$INTENT_BODY")"
INTENT_ID="$(printf '%s' "$INTENT" | json_field intent.id)"
UPLOAD_URL="$(printf '%s' "$INTENT" | json_field authorization.uploadUrl)"
test -n "$INTENT_ID" -a -n "$UPLOAD_URL" || {{ echo "Upload intent contract mismatch: $INTENT"; exit 1; }}
curl -fsS -X POST "$BASE/media/upload-intents/$INTENT_ID/fail" \
  "${{AUTH_HEADER[@]}}" -H 'Content-Type: application/json' \
  -d '{{"reasonCode":"verification_cleanup"}}' >/dev/null

echo "[verify] Event-driven search projection"
SEARCH_FOUND=0
for _ in 1 2 3 4 5 6 7 8; do
  SEARCH="$(curl -fsS "$BASE/post-search?q=$CONTENT" "${{AUTH_HEADER[@]}}")"
  if printf '%s' "$SEARCH" | python3 -c 'import json,sys
d=json.load(sys.stdin); expected=sys.argv[1]
raise SystemExit(0 if any(str(x.get("targetId")) == expected for x in d.get("items",[])) else 1)' "$POST_ID"; then
    SEARCH_FOUND=1
    break
  fi
  sleep 0.25
done
test "$SEARCH_FOUND" = "1" || {{ echo "Post $POST_ID was not indexed from its lifecycle event"; exit 1; }}

echo "[verify] Backend composition passed: auth + JWT + CORS + posts + BFF + comments + media intent + search events"
'''

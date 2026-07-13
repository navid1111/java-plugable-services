"""Per-app workspaces: a plain static-web folder the agent writes index.html/app.js
into, with the `plugs` skill mounted so the agent is grounded in real backends.

No npm/build step — app-builder serves the folder directly, so a generated app is
clickable the moment the agent writes index.html.
"""

import asyncio
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path

from .catalog import (
    fetch_catalog,
    render_agents_md,
    render_backend_verifier,
    render_frontend_contract_verifier,
    render_skill,
)
from .config import settings

# Shown before the agent writes anything, so a fresh workspace isn't a blank 404.
_PLACEHOLDER_INDEX = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Building…</title>
<style>body{font-family:system-ui;background:#0b1020;color:#e2e8f0;display:grid;place-items:center;height:100vh;margin:0}</style>
</head><body><div>The agent is building your app… this page updates when it writes <code>index.html</code>.</div></body></html>
"""


def _slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return slug or "app"


def new_slug(prompt: str) -> str:
    import secrets

    base = _slugify(prompt)[:32].strip("-") or "app"
    return f"{base}-{secrets.token_hex(2)}"


def workspace_dir(slug: str) -> Path:
    return settings.workspaces_dir / slug


def index_mtime(cwd: Path) -> float:
    entry = cwd / "index.html"
    return entry.stat().st_mtime if entry.exists() else 0.0


_SERVICE_PATH_MARKERS: dict[str, tuple[str, ...]] = {
    "auth-service": ("/auth/",),
    "bff": ("/bff/",),
    "booking-service": ("/bookings",),
    "comment-service": ("/comments",),
    "leetcode-service": ("/leetcode",),
    "media-service": ("/media",),
    "post-search-service": ("/post-search",),
    "tweeter-service": ("/posts",),
    "whatsapp-service": ("/chat",),
}


def _frontend_sources(cwd: Path) -> list[Path]:
    candidates = {*cwd.rglob("*.html"), *cwd.rglob("*.js")}
    return sorted(
        path for path in candidates
        if not any(part.startswith(".") for part in path.relative_to(cwd).parts)
    )


def services_used_by_frontend(cwd: Path) -> list[str]:
    """Map generated browser calls to the official plug smoke tests they require."""
    source = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in _frontend_sources(cwd)
    )
    return [
        service for service, markers in _SERVICE_PATH_MARKERS.items()
        if any(marker in source for marker in markers)
    ]


def _source_fingerprint(cwd: Path) -> str:
    digest = hashlib.sha256()
    for path in _frontend_sources(cwd):
        digest.update(str(path.relative_to(cwd)).encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _verification_record(cwd: Path) -> Path:
    return cwd.parent / ".verification" / f"{cwd.name}.json"


def live_verification_status(cwd: Path) -> tuple[bool, str]:
    """Return whether the exact currently-served sources passed live checks."""
    record = _verification_record(cwd)
    if not record.is_file():
        return False, "live endpoint verification has not passed for this build"
    try:
        data = json.loads(record.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False, "live endpoint verification record is invalid"
    if data.get("fingerprint") != _source_fingerprint(cwd):
        return False, "frontend changed after live endpoint verification"
    return True, "verified: " + ", ".join(data.get("services", []))


async def validate_live_backend_endpoints(cwd: Path) -> tuple[bool, str]:
    """Run official, server-owned smoke tests for every service used by the app.

    The generated agent cannot edit these scripts: they live beside each Java
    service, outside its workspace. A source fingerprint makes the pass stale as
    soon as HTML or JavaScript changes.
    """
    record = _verification_record(cwd)
    record.unlink(missing_ok=True)
    services = services_used_by_frontend(cwd)
    reports: list[str] = []
    deadline = asyncio.get_running_loop().time() + settings.backend_verification_timeout_seconds
    env = os.environ.copy()
    env.update({
        "KONG_URL": settings.gateway_url,
        "GATEWAY_URL": settings.gateway_url,
        "APPBUILDER_ORIGIN": f"http://localhost:{settings.port}",
    })

    for service in services:
        script = settings.service_smoke_root / service / "plug" / "smoke.sh"
        if not script.is_file():
            return False, f"{service}: official smoke test is missing"
        remaining = deadline - asyncio.get_running_loop().time()
        if remaining <= 0:
            return False, "live endpoint verification exceeded its time limit"
        process = await asyncio.create_subprocess_exec(
            "bash", str(script),
            cwd=settings.service_smoke_root,
            env=env,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
        try:
            stdout, _ = await asyncio.wait_for(process.communicate(), timeout=remaining)
        except TimeoutError:
            process.kill()
            await process.communicate()
            return False, f"{service}: smoke test timed out"
        output = stdout.decode(errors="replace").strip()
        reports.append(f"[{service}]\n{output[-3000:]}")
        if process.returncode != 0:
            return False, "\n".join(reports)

    record.parent.mkdir(parents=True, exist_ok=True)
    record.write_text(json.dumps({
        "fingerprint": _source_fingerprint(cwd),
        "services": services,
        "verifiedAt": int(time.time()),
    }, indent=2), encoding="utf-8")
    summary = "no backend calls" if not services else ", ".join(services)
    return True, f"live endpoint verification passed: {summary}"


async def validate_frontend_contracts(cwd: Path) -> tuple[bool, str]:
    """Run the server-owned contract linter against a generated workspace.

    The canonical verifier is passed directly to Python instead of trusting the
    workspace copy, which prevents a generated agent from weakening its own
    checks to make an invalid app appear healthy.
    """
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-c",
        render_frontend_contract_verifier(),
        str(cwd),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await process.communicate()
    report = (stdout + stderr).decode(errors="replace").strip()
    return process.returncode == 0, report


async def scaffold(slug: str) -> Path:
    """Create the workspace, seed a placeholder, and mount the freshly-rendered plugs skill."""
    cwd = workspace_dir(slug)
    cwd.mkdir(parents=True, exist_ok=True)

    entry = cwd / "index.html"
    if not entry.exists():
        entry.write_text(_PLACEHOLDER_INDEX)

    plugs, endpoints = await fetch_catalog()
    (cwd / "AGENTS.md").write_text(render_agents_md(plugs, endpoints))
    verifier = cwd / "verify-backend.sh"
    verifier.write_text(render_backend_verifier())
    verifier.chmod(0o755)
    frontend_verifier = cwd / "verify-frontend-contracts.py"
    frontend_verifier.write_text(render_frontend_contract_verifier())
    frontend_verifier.chmod(0o755)

    skill_dir = cwd / ".hermes" / "skills" / "plugs"
    skill_dir.mkdir(parents=True, exist_ok=True)
    (skill_dir / "SKILL.md").write_text(render_skill(plugs, endpoints))
    return cwd


def list_files(cwd: Path) -> list[str]:
    """Source files in the workspace (skips dotdirs), relative to the workspace root."""
    files: list[str] = []
    if not cwd.is_dir():
        return files
    for path in sorted(cwd.rglob("*")):
        if path.is_file() and not any(part.startswith(".") for part in path.relative_to(cwd).parts):
            files.append(str(path.relative_to(cwd)))
    return files


def safe_file(cwd: Path, rel: str) -> Path | None:
    """Resolve a request path to a file inside the workspace, or None if it escapes."""
    target = (cwd / rel).resolve()
    if not str(target).startswith(str(cwd.resolve())) or not target.is_file():
        return None
    return target

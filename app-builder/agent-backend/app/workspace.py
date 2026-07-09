"""Per-app workspaces: a plain static-web folder the agent writes index.html/app.js
into, with the `plugs` skill mounted so the agent is grounded in real backends.

No npm/build step — app-builder serves the folder directly, so a generated app is
clickable the moment the agent writes index.html.
"""

import re
from pathlib import Path

from .catalog import fetch_catalog, render_agents_md, render_backend_verifier, render_skill
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

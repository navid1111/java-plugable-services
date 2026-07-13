"""Per-app React workspaces, preview builds, and editable service architecture.

Generated agents only edit the small React source tree. App Builder owns the shared
Vite toolchain and compiles draft checkpoints, so agents never install dependencies
and users can see safe intermediate previews while a turn is still running.
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

_PLACEHOLDER_INDEX = """<!doctype html>
<html lang="en"><head><meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Generated React app</title></head>
<body><div id="root"></div><script type="module" src="/src/main.jsx"></script></body></html>
"""

_PLACEHOLDER_MAIN = """import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './styles.css';

createRoot(document.getElementById('root')).render(
  <React.StrictMode><App /></React.StrictMode>,
);
"""

_PLACEHOLDER_APP = """export default function App() {
  return (
    <main className="building-shell">
      <span>React workspace ready</span>
      <h1>Your app is being created</h1>
      <p>Draft previews will refresh here as each usable checkpoint is built.</p>
    </main>
  );
}
"""

_PLACEHOLDER_STYLES = """* { box-sizing: border-box; }
body { margin: 0; min-width: 320px; min-height: 100vh; font-family: Inter, system-ui, sans-serif;
  color: #e2e8f0; background: radial-gradient(circle at top, #172554, #070b16 55%); }
.building-shell { min-height: 100vh; display: grid; place-content: center; gap: 12px; padding: 32px; text-align: center; }
.building-shell span { color: #67e8f9; font-size: 12px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
.building-shell h1 { margin: 0; font-size: clamp(32px, 6vw, 64px); }
.building-shell p { max-width: 560px; margin: 0; color: #94a3b8; font-size: 17px; }
"""

_VITE_CONFIG = """export default {
  esbuild: { jsx: 'automatic' },
};
"""

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
_VITE_ENTRY = _BACKEND_ROOT / "node_modules" / "vite" / "bin" / "vite.js"
_PREVIEW_DIST = Path(".appbuilder") / "dist"
_FRONTEND_SUFFIXES = {".html", ".js", ".jsx", ".ts", ".tsx", ".css"}


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
    """Legacy compatibility: newest React/static source modification time."""
    sources = _frontend_sources(cwd)
    return max((path.stat().st_mtime for path in sources), default=0.0)


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

_SERVICE_DISPLAY_NAMES: dict[str, str] = {
    "auth-service": "Authentication",
    "bff": "Backend for Frontend",
    "booking-service": "Booking",
    "comment-service": "Comments",
    "leetcode-service": "Coding challenges",
    "media-service": "Media",
    "post-search-service": "Search",
    "tweeter-service": "Posts",
    "whatsapp-service": "Chat",
}
_ARCHITECTURE_FILE = "ARCHITECTURE.mmd"
_ARCHITECTURE_META = ".appbuilder/architecture.json"
_MAX_ARCHITECTURE_CHARS = 30_000
_MAX_ARCHITECTURE_NODES = 50
_MAX_ARCHITECTURE_EDGES = 100


def _frontend_sources(cwd: Path) -> list[Path]:
    if not cwd.is_dir():
        return []
    return sorted(
        path for path in cwd.rglob("*")
        if path.is_file()
        and path.suffix.lower() in _FRONTEND_SUFFIXES
        and not any(part.startswith(".") or part == "node_modules" for part in path.relative_to(cwd).parts)
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


def _architecture_hash(source: str) -> str:
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def _architecture_meta(cwd: Path) -> dict:
    try:
        data = json.loads((cwd / _ARCHITECTURE_META).read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _write_architecture_meta(cwd: Path, data: dict) -> None:
    target = cwd / _ARCHITECTURE_META
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")


def generate_architecture(cwd: Path) -> str:
    """Create a deterministic Mermaid view of the browser-to-plug wiring."""
    services = services_used_by_frontend(cwd)
    lines = [
        "flowchart LR",
        '  User([User]) --> App["Generated web app"]',
    ]
    if not services:
        lines.append('  App -. "No backend calls detected yet" .-> Static["Static interface"]')
        return "\n".join(lines) + "\n"

    lines.extend([
        '  App -->|"API requests; JWT when required"| Kong["Kong API Gateway<br/>localhost:18000"]',
        "  subgraph Plugs[Backend service plugs]",
    ])
    for index, service in enumerate(services):
        node = f"S{index}"
        display = _SERVICE_DISPLAY_NAMES.get(service, service)
        lines.append(f'    {node}["{display}<br/>{service}"]')
    lines.append("  end")
    for index, service in enumerate(services):
        gateway_paths = ", ".join(_SERVICE_PATH_MARKERS[service])
        lines.append(f'  Kong -->|"{gateway_paths}"| S{index}')
    return "\n".join(lines) + "\n"


def _generated_architecture_graph(services: list[str]) -> dict:
    nodes = [
        {"id": "user", "type": "actor", "label": "User", "x": 60, "y": 210},
        {"id": "app", "type": "app", "label": "React app", "x": 285, "y": 210},
        {"id": "gateway", "type": "gateway", "label": "Kong API Gateway", "x": 520, "y": 210},
    ]
    edges = [
        {"id": "user-app", "source": "user", "target": "app"},
        {"id": "app-gateway", "source": "app", "target": "gateway"},
    ]
    for index, service in enumerate(services):
        node_id = f"service:{service}"
        nodes.append({
            "id": node_id,
            "type": "service",
            "serviceId": service,
            "label": _SERVICE_DISPLAY_NAMES.get(service, service),
            "x": 760,
            "y": 70 + index * 125,
        })
        edges.append({"id": f"gateway-{service}", "source": "gateway", "target": node_id})
    return {"nodes": nodes, "edges": edges}


def _valid_architecture_graph(value: object) -> bool:
    return isinstance(value, dict) and isinstance(value.get("nodes"), list) and isinstance(value.get("edges"), list)


def _normalize_architecture_graph(value: dict) -> dict:
    nodes_raw = value.get("nodes", [])
    edges_raw = value.get("edges", [])
    if len(nodes_raw) > _MAX_ARCHITECTURE_NODES or len(edges_raw) > _MAX_ARCHITECTURE_EDGES:
        raise ValueError("Architecture graph is too large")

    nodes: list[dict] = []
    ids: set[str] = set()
    service_ids: set[str] = set()
    for item in nodes_raw:
        if not isinstance(item, dict):
            raise ValueError("Every architecture node must be an object")
        node_id = str(item.get("id", ""))[:100]
        node_type = str(item.get("type", ""))
        if not node_id or node_id in ids or node_type not in {"actor", "app", "gateway", "service"}:
            raise ValueError("Architecture contains an invalid or duplicate node")
        service_id = str(item.get("serviceId", ""))
        if node_type == "service" and service_id not in _SERVICE_PATH_MARKERS:
            raise ValueError(f"Unknown service plug: {service_id}")
        if node_type == "service" and service_id in service_ids:
            raise ValueError(f"Service plug is already present: {service_id}")
        if node_type == "service":
            service_ids.add(service_id)
        ids.add(node_id)
        node = {
            "id": node_id,
            "type": node_type,
            "label": str(item.get("label", node_id))[:120],
            "x": max(0, min(1600, int(float(item.get("x", 0))))),
            "y": max(0, min(1200, int(float(item.get("y", 0))))),
        }
        if service_id:
            node["serviceId"] = service_id
        nodes.append(node)

    if "app" not in ids or "gateway" not in ids:
        raise ValueError("Architecture must keep the app and gateway nodes")

    edges: list[dict] = []
    edge_ids: set[str] = set()
    for item in edges_raw:
        if not isinstance(item, dict):
            raise ValueError("Every architecture connection must be an object")
        source = str(item.get("source", ""))
        target = str(item.get("target", ""))
        edge_id = str(item.get("id") or f"{source}-{target}")[:160]
        if source not in ids or target not in ids or source == target or edge_id in edge_ids:
            raise ValueError("Architecture contains an invalid connection")
        edge_ids.add(edge_id)
        edges.append({"id": edge_id, "source": source, "target": target})
    return {"nodes": nodes, "edges": edges}


def _mermaid_from_graph(graph: dict) -> str:
    node_by_id = {node["id"]: node for node in graph["nodes"]}
    aliases = {node_id: f"N{index}" for index, node_id in enumerate(node_by_id)}
    lines = ["flowchart LR"]
    for node_id, node in node_by_id.items():
        label = str(node.get("label", node_id)).replace('"', "'")
        if node.get("type") == "service":
            label += f"<br/>{node.get('serviceId', '')}"
        shape = f'(["{label}"])' if node.get("type") == "actor" else f'["{label}"]'
        lines.append(f"  {aliases[node_id]}{shape}")
    for edge in graph["edges"]:
        source = aliases[edge["source"]]
        target = aliases[edge["target"]]
        target_node = node_by_id[edge["target"]]
        if target_node.get("type") == "service":
            paths = ", ".join(_SERVICE_PATH_MARKERS[target_node["serviceId"]])
            lines.append(f'  {source} -->|"{paths}"| {target}')
        else:
            lines.append(f"  {source} --> {target}")
    return "\n".join(lines) + "\n"


def architecture_payload(cwd: Path, *, refresh_generated: bool = False) -> dict:
    """Return the editable diagram, preserving user changes across regeneration."""
    target = cwd / _ARCHITECTURE_FILE
    meta = _architecture_meta(cwd)
    existing = target.read_text(encoding="utf-8") if target.is_file() else ""
    last_generated_hash = str(meta.get("lastGeneratedHash", ""))

    # A direct IDE edit is user intent too, even if it did not go through the API.
    manually_changed = bool(existing and last_generated_hash and _architecture_hash(existing) != last_generated_hash)
    untracked_existing = bool(existing and not last_generated_hash)
    user_owned = meta.get("origin") == "user" or manually_changed or untracked_existing

    services = services_used_by_frontend(cwd)
    graph = meta.get("graph") if _valid_architecture_graph(meta.get("graph")) else None

    if not existing or (refresh_generated and not user_owned):
        graph = _generated_architecture_graph(services)
        existing = _mermaid_from_graph(graph)
        target.write_text(existing, encoding="utf-8")
        meta = {
            "origin": "generated",
            "lastGeneratedHash": _architecture_hash(existing),
            "graph": graph,
        }
        _write_architecture_meta(cwd, meta)
    elif user_owned and meta.get("origin") != "user":
        meta["origin"] = "user"
        _write_architecture_meta(cwd, meta)

    if graph is None:
        graph = _generated_architecture_graph(services)
        meta["graph"] = graph
        _write_architecture_meta(cwd, meta)

    connected = {
        node.get("serviceId")
        for node in graph["nodes"]
        if node.get("type") == "service"
    }
    catalog = [
        {
            "id": service,
            "displayName": _SERVICE_DISPLAY_NAMES.get(service, service),
            "gatewayPaths": list(_SERVICE_PATH_MARKERS[service]),
            "connected": service in connected,
        }
        for service in _SERVICE_PATH_MARKERS
    ]
    return {
        "source": existing,
        "services": services,
        "origin": meta.get("origin", "user" if user_owned else "generated"),
        "graph": graph,
        "catalog": catalog,
        "connectedServices": sorted(service for service in connected if service),
    }


def save_user_architecture(cwd: Path, source: str = "", graph: dict | None = None) -> dict:
    normalized_graph = _normalize_architecture_graph(graph) if graph is not None else None
    normalized = _mermaid_from_graph(normalized_graph) if normalized_graph is not None else source.strip() + "\n"
    if len(normalized) > _MAX_ARCHITECTURE_CHARS:
        raise ValueError(f"Mermaid source must be at most {_MAX_ARCHITECTURE_CHARS} characters")
    if not re.match(
        r"^(flowchart|graph|sequenceDiagram|classDiagram|stateDiagram(?:-v2)?|erDiagram|journey|gantt|mindmap|timeline)\b",
        normalized,
    ):
        raise ValueError("Mermaid source must start with a supported diagram declaration such as 'flowchart LR'")
    target = cwd / _ARCHITECTURE_FILE
    target.write_text(normalized, encoding="utf-8")
    _write_architecture_meta(cwd, {
        "origin": "user",
        "lastGeneratedHash": _architecture_meta(cwd).get("lastGeneratedHash", ""),
        "graph": normalized_graph or _architecture_meta(cwd).get("graph"),
    })
    return architecture_payload(cwd)


def request_with_architecture(cwd: Path, request: str) -> str:
    architecture = architecture_payload(cwd)
    if architecture["origin"] == "user":
        guidance = (
            "The Mermaid diagram below is user-authored architecture intent. Use it as context, "
            "but only wire services and endpoints present in the plugs skill."
        )
    else:
        guidance = (
            "The Mermaid diagram below is an automatically detected snapshot of the previous/current "
            "frontend. It is context, not a constraint on this new request."
        )
    return (
        f"{request.rstrip()}\n\n"
        f"App Builder architecture context:\n{guidance}\n"
        f"```mermaid\n{architecture['source'].rstrip()}\n```"
    )


def _source_fingerprint(cwd: Path) -> str:
    digest = hashlib.sha256()
    for path in _frontend_sources(cwd):
        digest.update(str(path.relative_to(cwd)).encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def frontend_fingerprint(cwd: Path) -> str:
    return _source_fingerprint(cwd)


def preview_root(cwd: Path) -> Path:
    built = cwd / _PREVIEW_DIST
    return built if (built / "index.html").is_file() else cwd


async def build_frontend(cwd: Path) -> tuple[bool, str]:
    """Compile a React workspace with the server-owned shared Vite toolchain."""
    if not (cwd / "src" / "main.jsx").is_file():
        return ((cwd / "index.html").is_file(), "legacy static workspace")
    if not _VITE_ENTRY.is_file():
        return False, "shared React toolchain is missing; run npm install in app-builder/agent-backend"
    process = await asyncio.create_subprocess_exec(
        "node", str(_VITE_ENTRY), "build", str(cwd),
        "--base", "./", "--outDir", str(_PREVIEW_DIST), "--emptyOutDir",
        cwd=str(_BACKEND_ROOT),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    try:
        stdout, _ = await asyncio.wait_for(process.communicate(), timeout=60)
    except TimeoutError:
        process.kill()
        await process.communicate()
        return False, "React preview build timed out"
    report = stdout.decode(errors="replace").strip()
    return process.returncode == 0, report


def _verification_record(cwd: Path) -> Path:
    return cwd.parent / ".verification" / f"{cwd.name}.json"


def _service_verification_record(cwd: Path) -> Path:
    return cwd.parent / ".verification" / "service-smokes.json"


def _load_service_verifications(cwd: Path) -> dict[str, int]:
    record = _service_verification_record(cwd)
    try:
        data = json.loads(record.read_text(encoding="utf-8"))
        return {str(key): int(value) for key, value in data.items()}
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return {}


def _save_service_verifications(cwd: Path, values: dict[str, int]) -> None:
    record = _service_verification_record(cwd)
    record.parent.mkdir(parents=True, exist_ok=True)
    record.write_text(json.dumps(values, indent=2, sort_keys=True), encoding="utf-8")


def invalidate_live_verification(cwd: Path) -> None:
    _verification_record(cwd).unlink(missing_ok=True)


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
    invalidate_live_verification(cwd)
    services = services_used_by_frontend(cwd)
    reports: list[str] = []
    service_verifications = _load_service_verifications(cwd)
    deadline = asyncio.get_running_loop().time() + settings.backend_verification_timeout_seconds
    env = os.environ.copy()
    env.update({
        "KONG_URL": settings.gateway_url,
        "GATEWAY_URL": settings.gateway_url,
        "APPBUILDER_ORIGIN": f"http://localhost:{settings.port}",
    })

    for service in services:
        last_pass = service_verifications.get(service, 0)
        if int(time.time()) - last_pass <= settings.backend_verification_cache_seconds:
            reports.append(f"[{service}] recent server-owned smoke pass reused")
            continue
        script = settings.service_smoke_root / service / "plug" / "smoke.sh"
        if not script.is_file():
            return False, f"{service}: official smoke test is missing"
        for attempt in range(1, 4):
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
            reports.append(f"[{service} attempt {attempt}]\n{output[-3000:]}")
            if process.returncode == 0:
                service_verifications[service] = int(time.time())
                _save_service_verifications(cwd, service_verifications)
                break
            if "429" not in output or attempt == 3:
                return False, "\n".join(reports)
            delay = settings.backend_verification_rate_limit_retry_seconds
            if asyncio.get_running_loop().time() + delay >= deadline:
                return False, "\n".join(reports) + "\nrate limit did not reset before the verification deadline"
            await asyncio.sleep(delay)

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
    source_dir = cwd / "src"
    source_dir.mkdir(exist_ok=True)
    for name, source in (
        ("main.jsx", _PLACEHOLDER_MAIN),
        ("App.jsx", _PLACEHOLDER_APP),
        ("styles.css", _PLACEHOLDER_STYLES),
    ):
        target = source_dir / name
        if not target.exists():
            target.write_text(source)
    vite_config = cwd / "vite.config.mjs"
    if not vite_config.exists():
        vite_config.write_text(_VITE_CONFIG)

    await refresh_workspace_context(cwd)
    architecture_payload(cwd)
    await build_frontend(cwd)
    return cwd


async def refresh_workspace_context(cwd: Path) -> None:
    """Refresh generated instructions/guards without overwriting app source.

    This is also the migration entry for legacy static workspaces: their next agent
    turn receives the React contract while the currently working source remains intact.
    """
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

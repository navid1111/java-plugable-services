"""App-builder agent backend configuration (env-overridable via APPBUILDER_*)."""

import os
from pathlib import Path

from pydantic_settings import BaseSettings

# .../app-builder/agent-backend/app/config.py -> app-builder/agent-backend
BACKEND_ROOT = Path(__file__).resolve().parents[1]


class Settings(BaseSettings):
    model_config = {"env_prefix": "APPBUILDER_"}

    # Where the Java plug catalog is served (source of truth for backends + endpoints).
    catalog_url: str = "http://localhost:8080"
    # Kong gateway base URL that generated apps call at runtime.
    # The repo's docker-compose maps Kong's container port 8000 to host port 18000
    # because port 8000 is commonly occupied by other local dev services.
    gateway_url: str = "http://localhost:18000"

    # Per-app workspaces the agent writes into, and serves from.
    workspaces_dir: Path = BACKEND_ROOT / "workspaces"

    # Agent
    # An ordered, comma-separated preference list of backends. The first one that is
    # actually installed/importable wins; the rest are fallbacks. Options:
    #   codex-cli  -> the Codex CLI (uses the user's existing Codex auth)
    #   claude-cli -> the Claude Code CLI in headless mode (`claude -p`)
    #   hermes     -> the embedded Hermes Agent SDK
    # e.g. APPBUILDER_AGENT_BACKEND="codex-cli,claude-cli,hermes" tries Codex, then
    # falls back to Claude Code, then Hermes. A single value (e.g. "hermes") pins one.
    agent_backend: str = "codex-cli,claude-cli,hermes"
    model: str = ""
    provider: str = ""
    max_turns: int = 30
    hermes_source_path: Path = Path(os.environ.get("HERMES_AGENT_HOME", "~/.hermes/hermes-agent")).expanduser()
    codex_command: str = "codex"
    codex_sandbox: str = "workspace-write"
    codex_timeout_seconds: int = 600
    # Claude Code CLI fallback. `bypassPermissions` lets it write files + run the
    # verifier non-interactively inside the isolated workspace (peer of Codex's
    # `workspace-write` sandbox). Model defaults to `model` above, else Claude's default.
    claude_command: str = "claude"
    claude_permission_mode: str = "bypassPermissions"
    claude_timeout_seconds: int = 600

    host: str = "0.0.0.0"
    port: int = 8090

    @property
    def agent_backends(self) -> list[str]:
        """Ordered, de-duplicated backend preference list from `agent_backend`."""
        seen: list[str] = []
        for raw in self.agent_backend.split(","):
            name = raw.strip().lower()
            if name and name not in seen:
                seen.append(name)
        return seen

    @property
    def agent_path(self) -> str:
        # Give the agent's Bash tool the same PATH the server runs with.
        return os.environ.get("PATH", "")


settings = Settings()

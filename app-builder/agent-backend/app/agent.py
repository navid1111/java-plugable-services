"""Agent wiring for app-builder.

The backend can drive either the local Codex CLI or embedded Hermes. Both are
constrained to the generated app workspace; backend capabilities are exposed
through the workspace-local plugs skill rendered by :mod:`app.workspace`.
"""

from __future__ import annotations

import asyncio
import os
import sys
import tempfile
from collections.abc import Callable, Coroutine
from pathlib import Path
from typing import Any, Protocol

from .config import settings

APPBUILDER_SYSTEM_APPEND = """\
You are app-builder's frontend agent. You build small, self-contained web apps that
wire the user's pluggable Java backends through the Kong gateway.

Rules for every request:
- Build a STATIC frontend in the current working directory. No build step, no npm,
  no frameworks that need bundling. Write plain files: `index.html` plus optional
  `app.js` / `styles.css`. `index.html` is the entry the preview serves — always
  (over)write it, never a differently named entry.
- Read `AGENTS.md` and `.hermes/skills/plugs/SKILL.md` FIRST. They list the only
  backends and endpoints you may call, the `GATEWAY` base URL, the required JWT
  auth flow, and the backend verification command. Never invent an endpoint or a service.
- For a requested capability that has no backend in the skill, render a visible but
  DISABLED placeholder card labelled "Being developed — backend not available yet".
  Do not fake it as working.
- The UI can be modern and creative, but keep it to a few files. Inline small CSS/JS
  or use one app.js. Use fetch() to the GATEWAY endpoints; handle errors visibly.
- Be efficient with tools: you are already in the project directory — never `cd`.
  Don't create README/summary/doc files unless asked. Don't run servers or installers.
- If the app uses any backend fetch(), implement register/login or login, store the JWT,
  attach `Authorization: Bearer <token>` on protected calls, then run `./verify-backend.sh`.
  Treat a failing verifier as a real bug or infrastructure blocker; do not claim the app is wired.
- Finish with a 1-3 sentence summary of what you built and which backends it wires,
  not a long report.
"""

EventPublisher = Callable[[str, dict], Coroutine[Any, Any, None]]


class AppAgent(Protocol):
    async def run(self, text: str) -> None: ...
    async def interrupt(self) -> None: ...
    def history(self) -> list[dict[str, Any]]: ...


def create_app_agent(cwd: Path, publisher: EventPublisher, history: list[dict[str, Any]] | None = None) -> AppAgent:
    backend = settings.agent_backend.strip().lower()
    if backend in {"codex", "codex-cli", "codex_cli"}:
        return CodexCliAppAgent(cwd=cwd, publisher=publisher, history=history)
    if backend == "hermes":
        return HermesAppAgent(cwd=cwd, publisher=publisher, history=history)
    raise ValueError(
        "Unsupported APPBUILDER_AGENT_BACKEND "
        f"{settings.agent_backend!r}; use 'codex-cli' or 'hermes'."
    )


def _ensure_hermes_import_path() -> None:
    """Make the git-installed Hermes source importable when not pip-installed."""
    source_path = settings.hermes_source_path
    if source_path and source_path.exists():
        source = str(source_path)
        if source not in sys.path:
            sys.path.insert(0, source)


def _load_hermes_classes():
    _ensure_hermes_import_path()
    try:
        from run_agent import AIAgent  # type: ignore[import-not-found]
        from agent import runtime_cwd  # type: ignore[import-not-found]
    except Exception as exc:  # pragma: no cover - exercised at deployment time
        raise RuntimeError(
            "Hermes Agent SDK is not importable. Install hermes-agent or set "
            "APPBUILDER_HERMES_SOURCE_PATH to a Hermes Agent checkout."
        ) from exc
    return AIAgent, runtime_cwd


def _summarize_tool_input(name: str, tool_input: dict) -> str:
    if name in {"write_file", "read_file", "patch"}:
        return str(tool_input.get("path", ""))
    if name == "search_files":
        return str(tool_input.get("pattern", ""))
    return ", ".join(list(tool_input)[:4])


class HermesAppAgent:
    """Small async adapter around Hermes' synchronous AIAgent SDK."""

    def __init__(self, cwd: Path, publisher: EventPublisher, history: list[dict[str, Any]] | None = None) -> None:
        AIAgent, runtime_cwd = _load_hermes_classes()
        self.cwd = cwd.resolve()
        self._runtime_cwd = runtime_cwd
        self._loop = asyncio.get_running_loop()
        self._publisher = publisher
        self._history: list[dict[str, Any]] = history or []
        old_terminal_cwd = os.environ.get("TERMINAL_CWD")
        os.environ["TERMINAL_CWD"] = str(self.cwd)
        token = runtime_cwd.set_session_cwd(str(self.cwd))
        try:
            self._agent = AIAgent(
                model=settings.model,
                provider=settings.provider or None,
                max_iterations=settings.max_turns,
                enabled_toolsets=["file", "terminal"],
                quiet_mode=True,
                tool_progress_mode="all",
                ephemeral_system_prompt=APPBUILDER_SYSTEM_APPEND,
                skip_context_files=True,
                skip_memory=True,
                stream_delta_callback=self._on_stream_delta,
                thinking_callback=self._on_thinking,
                tool_start_callback=self._on_tool_start,
                tool_complete_callback=self._on_tool_complete,
            )
        finally:
            runtime_cwd._SESSION_CWD.reset(token)
            if old_terminal_cwd is None:
                os.environ.pop("TERMINAL_CWD", None)
            else:
                os.environ["TERMINAL_CWD"] = old_terminal_cwd

    async def run(self, text: str) -> None:
        result = await asyncio.to_thread(self._run_sync, text)
        self._history = result.get("messages") or self._history
        final = (result.get("final_response") or "").strip()
        if final:
            await self._publisher("assistant_text", {"text": final})
        await self._publisher(
            "done",
            {
                "is_error": bool(result.get("failed") or result.get("error")),
                "num_turns": result.get("api_calls"),
                "cost_usd": None,
                "session_id": getattr(self._agent, "session_id", None),
            },
        )

    async def interrupt(self) -> None:
        self._agent.interrupt()

    def history(self) -> list[dict[str, Any]]:
        return self._history

    def _run_sync(self, text: str) -> dict[str, Any]:
        old_terminal_cwd = os.environ.get("TERMINAL_CWD")
        os.environ["TERMINAL_CWD"] = str(self.cwd)
        token = self._runtime_cwd.set_session_cwd(str(self.cwd))
        try:
            return self._agent.run_conversation(
                user_message=text,
                conversation_history=self._history,
            )
        finally:
            self._runtime_cwd._SESSION_CWD.reset(token)
            if old_terminal_cwd is None:
                os.environ.pop("TERMINAL_CWD", None)
            else:
                os.environ["TERMINAL_CWD"] = old_terminal_cwd

    def _publish_from_thread(self, event_type: str, data: dict) -> None:
        self._loop.call_soon_threadsafe(
            lambda: asyncio.create_task(self._publisher(event_type, data))
        )

    def _on_stream_delta(self, delta: str | None) -> None:
        if delta:
            self._publish_from_thread("assistant_delta", {"text": delta})

    def _on_thinking(self, text: str) -> None:
        if text:
            self._publish_from_thread("thinking", {"text": text})

    def _on_tool_start(self, _tool_id: str, name: str, tool_input: dict) -> None:
        self._publish_from_thread(
            "tool_use",
            {"tool": name, "input": _summarize_tool_input(name, tool_input or {})},
        )

    def _on_tool_complete(
        self,
        _tool_id: str,
        _name: str,
        _tool_input: dict,
        result: Any,
    ) -> None:
        text = str(result or "")[:500]
        self._publish_from_thread("tool_result", {"ok": True, "summary": text})


class CodexCliAppAgent:
    """Adapter that runs the installed Codex CLI inside the app workspace."""

    def __init__(self, cwd: Path, publisher: EventPublisher, history: list[dict[str, Any]] | None = None) -> None:
        self.cwd = cwd.resolve()
        self._publisher = publisher
        self._process: asyncio.subprocess.Process | None = None
        self._history = history or []

    async def run(self, text: str) -> None:
        with tempfile.NamedTemporaryFile(
            prefix="codex-last-message-",
            suffix=".txt",
            dir=self.cwd,
            delete=False,
        ) as tmp:
            last_message = Path(tmp.name)

        cmd = self._command(last_message, text)
        await self._publisher("thinking", {"text": "Starting Codex CLI..."})
        try:
            self._process = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=str(self.cwd),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
            )
            output = await asyncio.wait_for(
                self._read_output(self._process),
                timeout=settings.codex_timeout_seconds,
            )
            exit_code = await self._process.wait()
        except asyncio.TimeoutError:
            await self.interrupt()
            await self._publisher("assistant_text", {"text": "Codex timed out before finishing."})
            await self._publisher("done", {"is_error": True, "num_turns": 1, "cost_usd": None})
            last_message.unlink(missing_ok=True)
            return
        finally:
            self._process = None

        final = self._read_last_message(last_message) or output.strip()
        if final:
            await self._publisher("assistant_text", {"text": final[-2000:]})
        await self._publisher(
            "done",
            {"is_error": exit_code != 0, "num_turns": 1, "cost_usd": None},
        )
        last_message.unlink(missing_ok=True)

    async def interrupt(self) -> None:
        if self._process and self._process.returncode is None:
            self._process.terminate()
            try:
                await asyncio.wait_for(self._process.wait(), timeout=5)
            except asyncio.TimeoutError:
                self._process.kill()

    def history(self) -> list[dict[str, Any]]:
        return self._history

    def _command(self, last_message: Path, text: str) -> list[str]:
        cmd = [
            settings.codex_command,
            "exec",
            "--cd",
            str(self.cwd),
            "--sandbox",
            settings.codex_sandbox,
            "--skip-git-repo-check",
            "--output-last-message",
            str(last_message),
            self._prompt(text),
        ]
        if settings.model:
            cmd[2:2] = ["--model", settings.model]
        return cmd

    async def _read_output(self, process: asyncio.subprocess.Process) -> str:
        chunks: list[str] = []
        assert process.stdout is not None
        while line := await process.stdout.readline():
            text = line.decode(errors="replace").rstrip()
            chunks.append(text)
            if text:
                await self._publisher("thinking", {"text": text[:500]})
        return "\n".join(chunks)

    def _read_last_message(self, path: Path) -> str:
        try:
            return path.read_text(errors="replace").strip()
        except OSError:
            return ""

    def _prompt(self, text: str) -> str:
        return f"""{APPBUILDER_SYSTEM_APPEND}

User request:
{text}

Before editing, read `AGENTS.md` and `.hermes/skills/plugs/SKILL.md`.
Build only the static app files in this directory. If you add or change backend fetch code,
run `./verify-backend.sh` before finishing and report the real result.
"""
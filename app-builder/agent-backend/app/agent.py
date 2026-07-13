"""Agent wiring for app-builder.

The backend can drive the local Codex CLI, the Claude Code CLI (headless), or
embedded Hermes. `settings.agent_backend` is an ordered preference list — the
first backend that is actually available is used, the rest are fallbacks — so a
missing/broken Codex install transparently falls back to Claude Code, then
Hermes. All backends are constrained to the generated app workspace; backend
capabilities are exposed through the workspace-local plugs skill rendered by
:mod:`app.workspace`.
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import logging
import os
import shutil
import sys
import tempfile
from collections.abc import Callable, Coroutine
from pathlib import Path
from typing import Any, Protocol

from .config import settings

logger = logging.getLogger(__name__)

_CONNECTION_RETRY_MESSAGE = "Connection interrupted briefly—continuing automatically."


def _codex_progress_event(text: str) -> dict[str, str] | None:
    """Turn Codex CLI diagnostics into safe, user-facing progress events."""
    lowered = text.lower()
    if "responses_retry" in lowered and "stream disconnected" in lowered:
        return {
            "text": _CONNECTION_RETRY_MESSAGE,
            "userMessage": _CONNECTION_RETRY_MESSAGE,
            "category": "connection_retry",
        }
    if "codex_core::" in lowered and any(level in lowered for level in (" warn ", " info ", " error ")):
        return None
    return {"text": text[:500]}

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
  auth flow, and the local contract verification command. Never invent an endpoint or a service.
- `ARCHITECTURE.mmd` is App Builder's user-editable architecture context. Read and honor
  user changes in it, but do not overwrite that file; App Builder keeps it synchronized.
- For a requested capability that has no backend in the skill, render a visible but
  DISABLED placeholder card labelled "Being developed — backend not available yet".
  Do not fake it as working.
- The UI can be modern and creative, but keep it to a few files. Inline small CSS/JS
  or use one app.js. Use fetch() to the GATEWAY endpoints; handle errors visibly.
- Be efficient with tools: you are already in the project directory — never `cd`.
  Don't create README/summary/doc files unless asked. Don't run servers or installers.
- If the app uses any backend fetch(), implement register/login or login, store the JWT,
  attach `Authorization: Bearer <token>` on protected calls, then run
  `python3 verify-frontend-contracts.py .`. Never edit, delete, weaken, or bypass that linter.
  Fix every reported frontend contract problem before finishing.
- Do NOT run `./verify-backend.sh` from the agent sandbox. Network sockets are intentionally
  unavailable there, so curl failures such as "Operation not permitted" are not backend results.
  App Builder's server independently reruns the canonical contract linter, CORS checks, and the
  official live smoke test for every referenced service before releasing the preview. A successful
  agent response is not completion; allow up to ten minutes for the server-owned gate.
- In async DOM event handlers, capture `const form = event.currentTarget` before the first `await`.
  Browsers may clear `event.currentTarget` after dispatch, so never access it after an `await`.
- Backend writes followed immediately by dependent writes can cross an asynchronous projection
  boundary. Follow the exact bounded-retry rules in the plugs skill; never paper over a 4xx with
  arbitrary payload fallbacks or broad retries.
- Finish with a 1-3 sentence summary of what you built and which backends it wires,
  not a long report.
"""

EventPublisher = Callable[[str, dict], Coroutine[Any, Any, None]]


class AppAgent(Protocol):
    async def run(self, text: str) -> None: ...
    async def interrupt(self) -> None: ...
    def history(self) -> list[dict[str, Any]]: ...


# Canonical backend name -> the aliases users may write in APPBUILDER_AGENT_BACKEND.
_BACKEND_ALIASES = {
    "codex-cli": {"codex", "codex-cli", "codex_cli"},
    "claude-cli": {"claude", "claude-cli", "claude_cli", "claude-code", "claude_code"},
    "hermes": {"hermes"},
}


def _canonical_backend(name: str) -> str | None:
    for canonical, aliases in _BACKEND_ALIASES.items():
        if name in aliases:
            return canonical
    return None


def _backend_available(canonical: str) -> bool:
    """Whether a backend's runtime is actually installed/importable."""
    if canonical == "codex-cli":
        return shutil.which(settings.codex_command) is not None
    if canonical == "claude-cli":
        return shutil.which(settings.claude_command) is not None
    if canonical == "hermes":
        if settings.hermes_source_path and settings.hermes_source_path.exists():
            return True
        return importlib.util.find_spec("run_agent") is not None
    return False


_BACKEND_FACTORIES = {
    "codex-cli": lambda cwd, publisher, history: CodexCliAppAgent(cwd=cwd, publisher=publisher, history=history),
    "claude-cli": lambda cwd, publisher, history: ClaudeCliAppAgent(cwd=cwd, publisher=publisher, history=history),
    "hermes": lambda cwd, publisher, history: HermesAppAgent(cwd=cwd, publisher=publisher, history=history),
}


def create_app_agent(cwd: Path, publisher: EventPublisher, history: list[dict[str, Any]] | None = None) -> AppAgent:
    requested = settings.agent_backends
    if not requested:
        raise ValueError("APPBUILDER_AGENT_BACKEND is empty; set at least one of 'codex-cli', 'claude-cli', 'hermes'.")

    attempted: list[str] = []
    for name in requested:
        canonical = _canonical_backend(name)
        if canonical is None:
            logger.warning("ignoring unknown APPBUILDER_AGENT_BACKEND entry %r", name)
            continue
        if not _backend_available(canonical):
            attempted.append(canonical)
            logger.info("agent backend %r unavailable; trying next fallback", canonical)
            continue
        try:
            agent = _BACKEND_FACTORIES[canonical](cwd, publisher, history)
        except Exception:  # available but failed to initialize — fall back to the next
            attempted.append(canonical)
            logger.warning("agent backend %r failed to start; trying next fallback", canonical, exc_info=True)
            continue
        if attempted:
            logger.warning("using agent backend %r after %s were unusable", canonical, attempted)
        else:
            logger.info("using agent backend %r", canonical)
        return agent

    raise ValueError(
        "No usable agent backend from APPBUILDER_AGENT_BACKEND "
        f"{settings.agent_backend!r}. Tried {attempted or requested} but none started. "
        "Install the Codex CLI, the Claude Code CLI (`claude`), or Hermes."
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
        published_categories: set[str] = set()
        assert process.stdout is not None
        while line := await process.stdout.readline():
            text = line.decode(errors="replace").rstrip()
            if not text:
                continue
            progress = _codex_progress_event(text)
            if progress is None:
                continue
            category = progress.get("category")
            if category:
                if category in published_categories:
                    continue
                published_categories.add(category)
            else:
                chunks.append(text)
            await self._publisher("thinking", progress)
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
run `python3 verify-frontend-contracts.py .` before finishing. Do not run the networked
`./verify-backend.sh` inside this sandbox; App Builder's server owns live endpoint verification.
"""


def _summarize_claude_tool_input(tool_input: dict) -> str:
    for key in ("file_path", "path", "command", "pattern", "query", "url"):
        value = tool_input.get(key)
        if value:
            return str(value)[:200]
    return ", ".join(list(tool_input)[:4])


class ClaudeCliAppAgent:
    """Adapter that runs the installed Claude Code CLI headlessly in the workspace.

    Mirrors :class:`CodexCliAppAgent` but drives `claude -p` with streaming JSON so
    tool use and assistant text surface as the same events the UI already renders.
    """

    def __init__(self, cwd: Path, publisher: EventPublisher, history: list[dict[str, Any]] | None = None) -> None:
        self.cwd = cwd.resolve()
        self._publisher = publisher
        self._process: asyncio.subprocess.Process | None = None
        self._history = history or []

    async def run(self, text: str) -> None:
        cmd = self._command(text)
        await self._publisher("thinking", {"text": "Starting Claude Code CLI..."})
        meta: dict[str, Any] = {"is_error": False, "num_turns": None, "cost_usd": None, "session_id": None}
        final_text = ""
        try:
            self._process = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=str(self.cwd),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
            )
            final_text, meta = await asyncio.wait_for(
                self._consume(self._process),
                timeout=settings.claude_timeout_seconds,
            )
            exit_code = await self._process.wait()
        except asyncio.TimeoutError:
            await self.interrupt()
            await self._publisher("assistant_text", {"text": "Claude Code timed out before finishing."})
            await self._publisher(
                "done",
                {"is_error": True, "num_turns": meta.get("num_turns"), "cost_usd": meta.get("cost_usd")},
            )
            return
        finally:
            self._process = None

        if final_text:
            await self._publisher("assistant_text", {"text": final_text[-2000:]})
        await self._publisher(
            "done",
            {
                "is_error": bool(meta.get("is_error")) or exit_code != 0,
                "num_turns": meta.get("num_turns"),
                "cost_usd": meta.get("cost_usd"),
                "session_id": meta.get("session_id"),
            },
        )

    async def interrupt(self) -> None:
        if self._process and self._process.returncode is None:
            self._process.terminate()
            try:
                await asyncio.wait_for(self._process.wait(), timeout=5)
            except asyncio.TimeoutError:
                self._process.kill()

    def history(self) -> list[dict[str, Any]]:
        return self._history

    def _command(self, text: str) -> list[str]:
        cmd = [
            settings.claude_command,
            "--print",
            "--output-format",
            "stream-json",
            "--verbose",
            "--permission-mode",
            settings.claude_permission_mode,
            "--append-system-prompt",
            APPBUILDER_SYSTEM_APPEND,
        ]
        if settings.model:
            cmd += ["--model", settings.model]
        cmd.append(self._prompt(text))
        return cmd

    async def _consume(self, process: asyncio.subprocess.Process) -> tuple[str, dict[str, Any]]:
        """Parse Claude Code's stream-json output into UI events; return (final_text, meta)."""
        final_text = ""
        meta: dict[str, Any] = {"is_error": False, "num_turns": None, "cost_usd": None, "session_id": None}
        assert process.stdout is not None
        while line := await process.stdout.readline():
            raw = line.decode(errors="replace").strip()
            if not raw:
                continue
            try:
                event = json.loads(raw)
            except json.JSONDecodeError:
                # Non-JSON line (merged stderr / diagnostics) — surface as progress.
                await self._publisher("thinking", {"text": raw[:500]})
                continue
            etype = event.get("type")
            if etype == "system":
                if event.get("session_id"):
                    meta["session_id"] = event["session_id"]
            elif etype == "assistant":
                for block in (event.get("message") or {}).get("content") or []:
                    btype = block.get("type")
                    if btype == "text" and block.get("text"):
                        await self._publisher("assistant_delta", {"text": block["text"]})
                    elif btype == "tool_use":
                        await self._publisher(
                            "tool_use",
                            {
                                "tool": block.get("name", ""),
                                "input": _summarize_claude_tool_input(block.get("input") or {}),
                            },
                        )
            elif etype == "result":
                final_text = (event.get("result") or "").strip()
                meta["is_error"] = bool(event.get("is_error"))
                meta["num_turns"] = event.get("num_turns")
                meta["cost_usd"] = event.get("total_cost_usd")
                if event.get("session_id"):
                    meta["session_id"] = event["session_id"]
        return final_text, meta

    def _prompt(self, text: str) -> str:
        # The app-builder system rules go via --append-system-prompt, so the user
        # prompt only carries the request plus the workspace-read reminder.
        return f"""User request:
{text}

Before editing, read `AGENTS.md` and `.hermes/skills/plugs/SKILL.md`.
Build only the static app files in this directory. If you add or change backend fetch code,
run `python3 verify-frontend-contracts.py .` before finishing. Do not run the networked
`./verify-backend.sh` inside this sandbox; App Builder's server owns live endpoint verification.
"""

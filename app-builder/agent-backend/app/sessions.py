"""Session manager: one long-lived app agent per app, serialized turns,
events relayed to the SSE bus. Emits a `preview` event whenever the agent writes
a newer index.html so the UI can refresh the live preview (VoltEdge's checkpoint
pattern, keyed on index.html mtime instead of circuit.json)."""

import asyncio
import logging
from dataclasses import dataclass, field
from pathlib import Path

from .agent import AppAgent, create_app_agent
from .chat_store import append_event, load_agent_history, save_agent_history
from .events import bus
from . import workspace

logger = logging.getLogger("appbuilder.sessions")

PERSISTED_EVENT_TYPES = {
    "user",
    "assistant_text",
    "tool_use",
    "tool_result",
    "preview",
    "error",
    "done",
    "build_complete",
    "verification",
}


@dataclass
class AppSession:
    slug: str
    cwd: Path
    agent: AppAgent
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    last_index_mtime: float = 0.0


class SessionManager:
    def __init__(self) -> None:
        self._sessions: dict[str, AppSession] = {}
        self._create_lock = asyncio.Lock()

    async def get_or_create(self, slug: str) -> AppSession:
        async with self._create_lock:
            session = self._sessions.get(slug)
            if session is not None:
                return session
            cwd = workspace.workspace_dir(slug)
            if not cwd.exists():
                await workspace.scaffold(slug)
            history = await load_agent_history(cwd)
            async def publish(event_type: str, data: dict) -> None:
                if event_type in PERSISTED_EVENT_TYPES:
                    await append_event(cwd, event_type, data)
                await bus.publish(slug, event_type, data)

            agent = create_app_agent(cwd=cwd, publisher=publish, history=history)
            session = AppSession(slug=slug, cwd=cwd, agent=agent,
                                 last_index_mtime=workspace.index_mtime(cwd))
            self._sessions[slug] = session
            return session

    def is_busy(self, slug: str) -> bool:
        session = self._sessions.get(slug)
        return session is not None and session.lock.locked()

    async def interrupt(self, slug: str) -> bool:
        session = self._sessions.get(slug)
        if session is None or not session.lock.locked():
            return False
        await session.agent.interrupt()
        return True

    async def run_turn(self, slug: str, text: str) -> None:
        """Drive one agent turn and relay SDK messages as SSE events."""
        try:
            session = await self.get_or_create(slug)
        except Exception as exc:
            logger.exception("could not start turn for app %s", slug)
            error = {
                "message": f"Could not start agent: {str(exc)[:400]}",
                "userMessage": "The builder could not start. Check that an agent backend is running, then try again.",
            }
            await bus.publish(slug, "error", error)
            await bus.publish(slug, "build_complete", {"is_error": True})
            return

        async with session.lock:
            try:
                await append_event(session.cwd, "user", {"text": text})
                await bus.publish(slug, "user", {"text": text})
                workspace.invalidate_live_verification(session.cwd)
                await session.agent.run(text)
                await save_agent_history(session.cwd, session.agent.history())
                valid, report = await workspace.validate_frontend_contracts(session.cwd)
                if not valid:
                    message = (
                        "Generated frontend was blocked by the server-owned contract verifier. "
                        + report[:1200]
                    )
                    data = {
                        "message": message,
                        "userMessage": (
                            "I found a backend connection problem during the final check, so the "
                            "broken preview was blocked. Ask the builder to fix the reported issue."
                        ),
                    }
                    await append_event(session.cwd, "error", data)
                    await bus.publish(slug, "error", data)
                    complete = {"is_error": True, "validation": "frontend-contracts"}
                    await append_event(session.cwd, "build_complete", complete)
                    await bus.publish(slug, "build_complete", complete)
                    return
                checking = {
                    "status": "running",
                    "userMessage": (
                        "The interface checks passed. Now testing every backend service used by this app; "
                        "this can take several minutes."
                    ),
                }
                await append_event(session.cwd, "verification", checking)
                await bus.publish(slug, "verification", checking)
                live_valid, live_report = await workspace.validate_live_backend_endpoints(session.cwd)
                if not live_valid:
                    data = {
                        "message": "Live endpoint verification failed. " + live_report[:5000],
                        "userMessage": (
                            "The app was built, but a real backend test failed, so I did not release the preview. "
                            "Ask the builder to fix the backend verification report."
                        ),
                    }
                    await append_event(session.cwd, "error", data)
                    await bus.publish(slug, "error", data)
                    complete = {"is_error": True, "validation": "live-endpoints"}
                    await append_event(session.cwd, "build_complete", complete)
                    await bus.publish(slug, "build_complete", complete)
                    return
                verified = {
                    "status": "passed",
                    "userMessage": "The real backend endpoint checks passed.",
                    "report": live_report[:1200],
                }
                await append_event(session.cwd, "verification", verified)
                await bus.publish(slug, "verification", verified)
                await self._maybe_preview(session)
                complete = {"is_error": False, "validation": "live-endpoints"}
                await append_event(session.cwd, "build_complete", complete)
                await bus.publish(slug, "build_complete", complete)
            except Exception as exc:
                logger.exception("turn failed for app %s", slug)
                data = {
                    "message": str(exc)[:500],
                    "userMessage": "Something interrupted the build. You can retry the same request.",
                }
                await append_event(session.cwd, "error", data)
                await bus.publish(slug, "error", data)
                complete = {"is_error": True}
                await append_event(session.cwd, "build_complete", complete)
                await bus.publish(slug, "build_complete", complete)

    async def _maybe_preview(self, session: AppSession) -> None:
        mtime = workspace.index_mtime(session.cwd)
        if mtime <= session.last_index_mtime:
            return
        session.last_index_mtime = mtime
        data = {"url": f"/apps/{session.slug}/"}
        await append_event(session.cwd, "preview", data)
        await bus.publish(session.slug, "preview", data)


manager = SessionManager()

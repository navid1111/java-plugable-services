"""Session manager: one long-lived app agent per app, serialized turns,
events relayed to the SSE bus. Emits a `preview` event whenever the agent writes
a newer index.html so the UI can refresh the live preview (VoltEdge's checkpoint
pattern, keyed on index.html mtime instead of circuit.json)."""

import asyncio
import logging
from dataclasses import dataclass, field
from pathlib import Path

from .agent import AppAgent, create_app_agent
from .events import bus
from . import workspace

logger = logging.getLogger("appbuilder.sessions")


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
            session_holder: dict[str, AppSession] = {}

            async def publish(event_type: str, data: dict) -> None:
                await bus.publish(slug, event_type, data)
                if event_type in ("tool_result", "done"):
                    await self._maybe_preview(session_holder["session"])

            agent = create_app_agent(cwd=cwd, publisher=publish)
            session = AppSession(slug=slug, cwd=cwd, agent=agent,
                                 last_index_mtime=workspace.index_mtime(cwd))
            session_holder["session"] = session
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
        await bus.publish(slug, "user", {"text": text})
        try:
            session = await self.get_or_create(slug)
        except Exception as exc:
            logger.exception("could not start turn for app %s", slug)
            await bus.publish(slug, "error", {"message": f"Could not start agent: {str(exc)[:400]}"})
            return

        async with session.lock:
            try:
                await session.agent.run(text)
                await self._maybe_preview(session)
            except Exception as exc:
                logger.exception("turn failed for app %s", slug)
                await bus.publish(slug, "error", {"message": str(exc)[:500]})

    async def _maybe_preview(self, session: AppSession) -> None:
        mtime = workspace.index_mtime(session.cwd)
        if mtime <= session.last_index_mtime:
            return
        session.last_index_mtime = mtime
        await bus.publish(session.slug, "preview", {"url": f"/apps/{session.slug}/"})


manager = SessionManager()

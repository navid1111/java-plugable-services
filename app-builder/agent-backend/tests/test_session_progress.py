from __future__ import annotations

import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.sessions import AppSession, SessionManager
from app.workspace import frontend_fingerprint


class FakeAgent:
    def __init__(self) -> None:
        self.last_text = ""

    async def run(self, _text: str) -> None:
        self.last_text = _text

    async def interrupt(self) -> None:
        return None

    def history(self) -> list[dict]:
        return []


class WritingAgent(FakeAgent):
    def __init__(self, cwd: Path) -> None:
        super().__init__()
        self.cwd = cwd

    async def run(self, text: str) -> None:
        self.last_text = text
        await asyncio.sleep(0.1)
        (self.cwd / "src" / "App.jsx").write_text("export default function App(){ return <h1>Draft ready</h1>; }")
        await asyncio.sleep(0.9)


class SessionProgressTest(unittest.IsolatedAsyncioTestCase):

    async def test_react_source_changes_publish_a_draft_before_verified_preview(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cwd = Path(temporary)
            (cwd / "src").mkdir()
            (cwd / "src" / "App.jsx").write_text("export default function App(){ return <h1>Starting</h1>; }")
            manager = SessionManager()
            session = AppSession("demo", cwd, WritingAgent(cwd))
            session.last_preview_fingerprint = frontend_fingerprint(cwd)
            manager._sessions["demo"] = session

            with (
                patch("app.sessions.append_event", new=AsyncMock()),
                patch("app.sessions.save_agent_history", new=AsyncMock()),
                patch("app.sessions.bus.publish", new=AsyncMock()) as publish,
                patch("app.sessions.workspace.build_frontend", new=AsyncMock(return_value=(True, "built"))),
                patch("app.sessions.workspace.validate_frontend_contracts", new=AsyncMock(return_value=(True, "passed"))),
                patch("app.sessions.workspace.validate_live_backend_endpoints", new=AsyncMock(return_value=(True, "live passed"))),
            ):
                await manager.run_turn("demo", "build a React screen")

            previews = [call.args[2] for call in publish.await_args_list if call.args[1] == "preview"]
            self.assertEqual("draft", previews[0]["stage"])
            self.assertEqual("verified", previews[-1]["stage"])
            self.assertIn("/draft/", previews[0]["url"])

    async def test_user_architecture_is_sent_to_agent_and_update_is_published(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cwd = Path(temporary)
            (cwd / "index.html").write_text("<!doctype html><title>Ready</title>")
            (cwd / "ARCHITECTURE.mmd").write_text("flowchart LR\n  App --> Booking\n")
            (cwd / ".appbuilder").mkdir()
            (cwd / ".appbuilder" / "architecture.json").write_text('{"origin":"user"}')
            agent = FakeAgent()
            manager = SessionManager()
            manager._sessions["demo"] = AppSession("demo", cwd, agent)

            with (
                patch("app.sessions.append_event", new=AsyncMock()),
                patch("app.sessions.save_agent_history", new=AsyncMock()),
                patch("app.sessions.bus.publish", new=AsyncMock()) as publish,
                patch("app.sessions.workspace.validate_frontend_contracts", new=AsyncMock(return_value=(True, "passed"))),
                patch("app.sessions.workspace.validate_live_backend_endpoints", new=AsyncMock(return_value=(True, "live passed"))),
            ):
                await manager.run_turn("demo", "use my diagram")

            self.assertIn("App --> Booking", agent.last_text)
            self.assertIn("user-authored architecture intent", agent.last_text)
            self.assertIn("architecture", [call.args[1] for call in publish.await_args_list])

    async def test_completion_is_published_only_after_server_validation_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cwd = Path(temporary)
            (cwd / "index.html").write_text("<!doctype html><title>Ready</title>")
            manager = SessionManager()
            manager._sessions["demo"] = AppSession("demo", cwd, FakeAgent())

            with (
                patch("app.sessions.append_event", new=AsyncMock()) as append,
                patch("app.sessions.save_agent_history", new=AsyncMock()),
                patch("app.sessions.bus.publish", new=AsyncMock()) as publish,
                patch("app.sessions.workspace.validate_frontend_contracts", new=AsyncMock(return_value=(True, "passed"))),
                patch("app.sessions.workspace.validate_live_backend_endpoints", new=AsyncMock(return_value=(True, "live passed"))),
            ):
                await manager.run_turn("demo", "build it")

            published_types = [call.args[1] for call in publish.await_args_list]
            persisted_types = [call.args[1] for call in append.await_args_list]
            self.assertEqual("build_complete", published_types[-1])
            self.assertEqual("build_complete", persisted_types[-1])
            self.assertFalse(publish.await_args_list[-1].args[2]["is_error"])

    async def test_failed_validation_gets_a_friendly_error_and_failed_completion(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cwd = Path(temporary)
            manager = SessionManager()
            manager._sessions["demo"] = AppSession("demo", cwd, FakeAgent())

            with (
                patch("app.sessions.append_event", new=AsyncMock()),
                patch("app.sessions.save_agent_history", new=AsyncMock()),
                patch("app.sessions.bus.publish", new=AsyncMock()) as publish,
                patch(
                    "app.sessions.workspace.validate_frontend_contracts",
                    new=AsyncMock(return_value=(False, "bad backend contract")),
                ),
                patch("app.sessions.workspace.validate_live_backend_endpoints", new=AsyncMock()),
            ):
                await manager.run_turn("demo", "build it")

            error = next(call.args[2] for call in publish.await_args_list if call.args[1] == "error")
            complete = publish.await_args_list[-1].args[2]
            self.assertIn("final check", error["userMessage"])
            self.assertTrue(complete["is_error"])

    async def test_live_endpoint_failure_blocks_preview_and_completion(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cwd = Path(temporary)
            (cwd / "index.html").write_text("<!doctype html><title>Ready</title>")
            manager = SessionManager()
            manager._sessions["demo"] = AppSession("demo", cwd, FakeAgent())

            with (
                patch("app.sessions.append_event", new=AsyncMock()),
                patch("app.sessions.save_agent_history", new=AsyncMock()),
                patch("app.sessions.bus.publish", new=AsyncMock()) as publish,
                patch("app.sessions.workspace.validate_frontend_contracts", new=AsyncMock(return_value=(True, "passed"))),
                patch(
                    "app.sessions.workspace.validate_live_backend_endpoints",
                    new=AsyncMock(return_value=(False, "booking-service returned 503")),
                ),
            ):
                await manager.run_turn("demo", "build it")

            event_types = [call.args[1] for call in publish.await_args_list]
            self.assertIn("verification", event_types)
            self.assertNotIn("preview", event_types)
            self.assertEqual("live-endpoints", publish.await_args_list[-1].args[2]["validation"])
            self.assertTrue(publish.await_args_list[-1].args[2]["is_error"])


if __name__ == "__main__":
    unittest.main()

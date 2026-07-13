from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.sessions import AppSession, SessionManager


class FakeAgent:
    async def run(self, _text: str) -> None:
        return None

    async def interrupt(self) -> None:
        return None

    def history(self) -> list[dict]:
        return []


class SessionProgressTest(unittest.IsolatedAsyncioTestCase):

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
            ):
                await manager.run_turn("demo", "build it")

            error = next(call.args[2] for call in publish.await_args_list if call.args[1] == "error")
            complete = publish.await_args_list[-1].args[2]
            self.assertIn("final check", error["userMessage"])
            self.assertTrue(complete["is_error"])


if __name__ == "__main__":
    unittest.main()

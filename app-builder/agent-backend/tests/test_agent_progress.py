from __future__ import annotations

import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.agent import ClaudeCliAppAgent, CodexCliAppAgent, _codex_progress_event


class AgentProgressTest(unittest.TestCase):

    def test_cli_prompts_keep_live_network_checks_outside_the_agent_sandbox(self) -> None:
        for agent_type in (CodexCliAppAgent, ClaudeCliAppAgent):
            agent = object.__new__(agent_type)
            prompt = agent._prompt("update the app")
            self.assertIn("python3 verify-frontend-contracts.py .", prompt)
            self.assertIn("Do not run the networked", prompt)
            self.assertIn("server owns live endpoint verification", prompt)

    def test_stream_retry_warning_becomes_one_plain_language_status(self) -> None:
        event = _codex_progress_event(
            "2026-07-13T20:11:28Z WARN codex_core::responses_retry: "
            "stream disconnected - retrying sampling request (1/5 in 209ms)"
        )

        self.assertEqual("connection_retry", event["category"])
        self.assertNotIn("codex_core", event["text"])
        self.assertIn("continuing automatically", event["userMessage"])

    def test_other_internal_codex_diagnostics_are_not_user_progress(self) -> None:
        event = _codex_progress_event(
            "2026-07-13T20:11:28Z WARN codex_core::transport: internal diagnostic"
        )

        self.assertIsNone(event)


class CodexOutputStreamTest(unittest.IsolatedAsyncioTestCase):

    async def test_repeated_retry_diagnostics_publish_only_one_friendly_event(self) -> None:
        stdout = asyncio.StreamReader()
        stdout.feed_data(
            b"2026-07-13T20:11:28Z WARN codex_core::responses_retry: "
            b"stream disconnected - retrying sampling request (1/5)\n"
            b"2026-07-13T20:11:29Z WARN codex_core::responses_retry: "
            b"stream disconnected - retrying sampling request (2/5)\n"
        )
        stdout.feed_eof()
        publisher = AsyncMock()
        agent = object.__new__(CodexCliAppAgent)
        agent._publisher = publisher

        output = await agent._read_output(SimpleNamespace(stdout=stdout))

        self.assertEqual("", output)
        publisher.assert_awaited_once()
        self.assertEqual("connection_retry", publisher.await_args.args[1]["category"])


if __name__ == "__main__":
    unittest.main()

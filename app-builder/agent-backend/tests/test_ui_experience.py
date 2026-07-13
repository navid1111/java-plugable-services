from __future__ import annotations

import unittest

from app.sessions import PERSISTED_EVENT_TYPES
from app.ui import INDEX_HTML


class BuilderExperienceTest(unittest.TestCase):

    def test_progress_is_plain_language_and_clearly_estimated(self) -> None:
        self.assertIn('role="progressbar"', INDEX_HTML)
        self.assertIn("Estimated progress", INDEX_HTML)
        self.assertIn("Usually 5–10 minutes with backend tests", INDEX_HTML)
        self.assertIn("Your app is ready", INDEX_HTML)
        self.assertIn("larger apps can take a little longer", INDEX_HTML)

    def test_technical_agent_output_is_collapsed_and_completion_is_server_confirmed(self) -> None:
        self.assertIn("<details id=\"technical\">", INDEX_HTML)
        self.assertIn("Technical details", INDEX_HTML)
        self.assertIn("'build_complete'", INDEX_HTML)
        self.assertIn("build_complete", PERSISTED_EVENT_TYPES)
        self.assertIn("verification", PERSISTED_EVENT_TYPES)
        self.assertIn("data.userMessage", INDEX_HTML)

    def test_editable_mermaid_architecture_is_visible_and_can_update_the_agent(self) -> None:
        self.assertIn('id="architectureTab"', INDEX_HTML)
        self.assertIn('id="architectureSource"', INDEX_HTML)
        self.assertIn("mermaid@11.12.2", INDEX_HTML)
        self.assertIn("Save &amp; update app", INDEX_HTML)
        self.assertIn("/architecture", INDEX_HTML)
        self.assertIn("architecture", PERSISTED_EVENT_TYPES)


if __name__ == "__main__":
    unittest.main()

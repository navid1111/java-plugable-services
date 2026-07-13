from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.config import settings
from app.routes import serve_index
from app.workspace import scaffold, validate_frontend_contracts


class WorkspaceGuardrailTest(unittest.IsolatedAsyncioTestCase):

    async def test_every_scaffold_contains_executable_contract_and_backend_verifiers(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch.object(settings, "workspaces_dir", root),
                patch("app.workspace.fetch_catalog", new=AsyncMock(return_value=([], {}))),
            ):
                workspace = await scaffold("guarded-app")

            frontend = workspace / "verify-frontend-contracts.py"
            backend = workspace / "verify-backend.sh"
            self.assertTrue(frontend.is_file())
            self.assertTrue(backend.is_file())
            self.assertTrue(frontend.stat().st_mode & 0o100)
            self.assertTrue(backend.stat().st_mode & 0o100)
            self.assertIn("verify-frontend-contracts.py", backend.read_text())
            self.assertIn("Do not edit, delete, weaken, or bypass", (workspace / "AGENTS.md").read_text())

    async def test_server_owned_lint_cannot_be_bypassed_by_editing_workspace_verifier(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "index.html").write_text(
                """<script>
                localStorage.getItem('appbuilder.jwt');
                fetch('/internal/media', {headers: {Authorization: 'Bearer token'}});
                </script>"""
            )
            (root / "verify-frontend-contracts.py").write_text(
                "#!/usr/bin/env python3\nraise SystemExit(0)\n"
            )

            valid, report = await validate_frontend_contracts(root)

            self.assertFalse(valid)
            self.assertIn("must not call /internal", report)

            with patch("app.routes.workspace.workspace_dir", return_value=root):
                response = await serve_index("guarded-app")

            self.assertEqual(409, response.status_code)
            self.assertIn(b"Preview blocked", response.body)


if __name__ == "__main__":
    unittest.main()

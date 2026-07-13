from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.config import settings
from app.routes import serve_index
from app.workspace import (
    architecture_payload,
    live_verification_status,
    request_with_architecture,
    save_user_architecture,
    scaffold,
    services_used_by_frontend,
    validate_frontend_contracts,
    validate_live_backend_endpoints,
)


class WorkspaceGuardrailTest(unittest.IsolatedAsyncioTestCase):

    async def test_architecture_is_generated_from_the_same_detected_service_plugs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "index.html").write_text('<script src="app.js"></script>')
            (root / "app.js").write_text(
                'fetch(GATEWAY + "/auth/me"); fetch(GATEWAY + "/comments/targets/post/7");'
            )

            architecture = architecture_payload(root, refresh_generated=True)

            self.assertEqual(["auth-service", "comment-service"], architecture["services"])
            self.assertIn("Kong API Gateway", architecture["source"])
            self.assertIn("auth-service", architecture["source"])
            self.assertIn("comment-service", architecture["source"])
            self.assertEqual(architecture["source"], (root / "ARCHITECTURE.mmd").read_text())

    async def test_user_architecture_survives_regeneration_and_becomes_agent_context(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "index.html").write_text("<!doctype html><title>App</title>")
            architecture_payload(root, refresh_generated=True)
            edited = "flowchart TD\n  Browser --> Gateway\n  Gateway --> Booking\n"

            saved = save_user_architecture(root, edited)
            (root / "app.js").write_text('fetch(GATEWAY + "/posts/feed")')
            refreshed = architecture_payload(root, refresh_generated=True)
            request = request_with_architecture(root, "Make the booking screen clearer")

            self.assertEqual("user", saved["origin"])
            self.assertEqual(edited, refreshed["source"])
            self.assertIn("user-authored architecture intent", request)
            self.assertIn("Gateway --> Booking", request)

    async def test_preexisting_mermaid_file_is_treated_as_user_context(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = "flowchart LR\n  App --> CustomContext\n"
            (root / "ARCHITECTURE.mmd").write_text(source)

            architecture = architecture_payload(root, refresh_generated=True)

            self.assertEqual("user", architecture["origin"])
            self.assertEqual(source, architecture["source"])

    async def test_invalid_architecture_source_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "must start"):
                save_user_architecture(Path(temporary), "this is not Mermaid")

    async def test_frontend_services_select_their_official_smoke_tests(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "index.html").write_text('<script src="app.js"></script>')
            (root / "app.js").write_text(
                'fetch(GATEWAY + "/auth/me"); fetch(GATEWAY + "/bookings/resources"); '
                'fetch(GATEWAY + "/posts/feed");'
            )

            self.assertEqual(
                ["auth-service", "booking-service", "tweeter-service"],
                services_used_by_frontend(root),
            )

    async def test_static_app_gets_a_fingerprinted_live_verification_record(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "workspaces" / "static-app"
            root.mkdir(parents=True)
            source = root / "index.html"
            source.write_text("<!doctype html><title>Static</title>")

            valid, _ = await validate_live_backend_endpoints(root)
            self.assertTrue(valid)
            self.assertTrue(live_verification_status(root)[0])

            source.write_text("<!doctype html><title>Changed</title>")
            self.assertFalse(live_verification_status(root)[0])

    async def test_recent_server_owned_smoke_pass_is_reused_without_consuming_rate_limit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            workspace = base / "workspaces" / "auth-app"
            workspace.mkdir(parents=True)
            (workspace / "app.js").write_text('fetch(GATEWAY + "/auth/me")')
            script = base / "services" / "auth-service" / "plug" / "smoke.sh"
            script.parent.mkdir(parents=True)
            script.write_text("#!/usr/bin/env bash\nexit 0\n")

            with (
                patch.object(settings, "service_smoke_root", base / "services"),
                patch.object(settings, "backend_verification_cache_seconds", 300),
            ):
                self.assertTrue((await validate_live_backend_endpoints(workspace))[0])
                script.unlink()
                valid, _ = await validate_live_backend_endpoints(workspace)

            self.assertTrue(valid)

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
            self.assertTrue((workspace / "ARCHITECTURE.mmd").is_file())
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

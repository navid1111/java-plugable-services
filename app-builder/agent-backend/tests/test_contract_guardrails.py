from __future__ import annotations

import unittest

from app.agent import APPBUILDER_SYSTEM_APPEND
from app.catalog import (
    render_agents_md,
    render_backend_verifier,
    render_frontend_contract_verifier,
    render_skill,
)


AVAILABLE_AUTH = [{
    "id": "auth-service",
    "displayName": "Auth Service",
    "status": "AVAILABLE",
    "gatewayPaths": ["/auth"],
}]


def frontend_lint(source: str) -> list[str]:
    namespace = {"__name__": "contract_lint_test"}
    exec(compile(render_frontend_contract_verifier(), "verify-frontend-contracts.py", "exec"), namespace)
    return namespace["lint"](source)


class ContractGuardrailTest(unittest.TestCase):

    def test_internal_endpoints_are_filtered_even_if_catalog_is_stale(self) -> None:
        endpoints = {"auth-service": ["GET /auth/me", "GET /internal/users/export"]}

        skill = render_skill(AVAILABLE_AUTH, endpoints)
        agents = render_agents_md(AVAILABLE_AUTH, endpoints)

        self.assertIn("`GET /auth/me`", skill)
        self.assertNotIn("`GET /internal/users/export`", skill)
        self.assertNotIn("`GET /internal/users/export`", agents)

    def test_known_dangerous_frontend_contracts_fail_lint(self) -> None:
        cases = {
            "internal workload API": 'fetch(GATEWAY + "/internal/users/export")',
            "destructive logout": 'api("/auth/me", { method: "DELETE" })',
            "removed search write": 'api("/post-search/documents/post/1", { method: "PUT" })',
            "guessed write bodies": 'postWithFallback("/comments", [{content: text}, {body: text}])',
            "manual multipart header": 'new FormData(); headers["Content-Type"] = "multipart/form-data"',
            "follow without username": 'api("/posts/users/id/follow", { method: "PUT" }); "appbuilder.jwt Authorization Bearer"',
        }
        for name, source in cases.items():
            with self.subTest(name=name):
                self.assertTrue(frontend_lint(source), name)

    def test_async_handlers_must_capture_current_target_before_await(self) -> None:
        broken = '''
        async function submit(event) {
          await api("/auth/register", {method: "POST"});
          event.currentTarget.reset();
        }
        '''

        self.assertTrue(any("currentTarget" in problem for problem in frontend_lint(broken)))

    def test_booking_write_requires_a_selected_slot(self) -> None:
        broken = '''
        const token = localStorage.getItem("appbuilder.jwt");
        const Authorization = "Bearer " + token;
        api("/bookings", {method: "POST", body: JSON.stringify({resourceId})});
        '''

        problems = frontend_lint(broken)

        self.assertTrue(any("slotId" in problem for problem in problems))
        self.assertTrue(any("available slot" in problem for problem in problems))

    def test_incomplete_upload_intent_and_projection_flow_fails_lint(self) -> None:
        source = '''
        const token = localStorage.getItem("appbuilder.jwt");
        const Authorization = "Bearer " + token;
        api("/posts", {method: "POST"});
        api("/media/upload-intents", {method: "POST"});
        '''

        problems = frontend_lint(source)

        self.assertTrue(any("idempotencyKey" in problem for problem in problems))
        self.assertTrue(any("target-projection readiness" in problem for problem in problems))

    def test_direct_multipart_media_write_also_requires_projection_readiness(self) -> None:
        source = '''
        const token = localStorage.getItem("appbuilder.jwt");
        const Authorization = "Bearer " + token;
        api("/posts", {method: "POST", body: JSON.stringify({content: "video"})});
        api("/media/targets/post/" + postId, {method: "POST", body: new FormData()});
        '''

        problems = frontend_lint(source)

        self.assertTrue(any("target-projection readiness" in problem for problem in problems))

    def test_comment_after_post_also_requires_projection_readiness(self) -> None:
        source = '''
        const token = localStorage.getItem("appbuilder.jwt");
        const Authorization = "Bearer " + token;
        api("/posts", {method: "POST", body: JSON.stringify({content: "problem"})});
        api("/comments/targets/post/" + postId, {method: "POST", body: JSON.stringify({content: "solution"})});
        '''

        problems = frontend_lint(source)

        self.assertTrue(any("target-projection readiness" in problem for problem in problems))

    def test_leetcode_admin_requires_role_gate_and_complete_problem_contract(self) -> None:
        incomplete = '''
        const token = localStorage.getItem("appbuilder.jwt");
        const Authorization = "Bearer " + token;
        api("/leetcode/admin/problems", {method: "POST", body: "{}"});
        '''

        problems = frontend_lint(incomplete)

        self.assertTrue(any("ADMIN role" in problem for problem in problems))
        self.assertTrue(any("testCases" in problem for problem in problems))
        self.assertTrue(any("codeStubs" in problem for problem in problems))

    def test_complete_upload_intent_flow_passes_lint(self) -> None:
        source = '''
        const token = localStorage.getItem("appbuilder.jwt");
        const headers = { Authorization: "Bearer " + token };
        api("/posts", {method: "POST", body: JSON.stringify({content: "video"})});
        api("/media/targets/post/" + postId + "/summary");
        await pause(200); setTimeout(() => {}, 1);
        if (message.includes("target does not exist or is deleted")) retry();
        api("/media/upload-intents", {method: "POST", body: JSON.stringify({
          targetType: "post", targetId: String(postId), idempotencyKey: "unique",
          resourceType: "image", format: "png", bytes: file.size
        })});
        const {intent, authorization} = response;
        form.append("api_key", authorization.apiKey);
        form.append("signature", authorization.signature);
        form.append("public_id", authorization.publicId);
        fetch(authorization.uploadUrl, {method: "POST", body: form});
        const finalized = {publicId, secureUrl};
        const failed = {reasonCode: "provider_failure"};
        '''

        self.assertEqual([], frontend_lint(source))

    def test_multipart_documentation_without_manual_header_is_allowed(self) -> None:
        source = '<p>Uploads use multipart/form-data.</p><script>const form = new FormData();</script>'

        self.assertEqual([], frontend_lint(source))

    def test_required_verifier_cannot_skip_frontend_lint(self) -> None:
        verifier = render_backend_verifier()
        agents = render_agents_md(AVAILABLE_AUTH, {"auth-service": ["GET /auth/me"]})

        self.assertLess(
            verifier.index("verify-frontend-contracts.py"),
            verifier.index("CORS preflight"),
        )
        self.assertIn('dirname "${BASH_SOURCE[0]}"', verifier)
        self.assertIn("Never edit", APPBUILDER_SYSTEM_APPEND)
        self.assertIn("arbitrary payload fallbacks", APPBUILDER_SYSTEM_APPEND)
        self.assertIn("python3 verify-frontend-contracts.py .", APPBUILDER_SYSTEM_APPEND)
        self.assertIn("Do NOT run `./verify-backend.sh`", APPBUILDER_SYSTEM_APPEND)
        self.assertIn("python3 verify-frontend-contracts.py .", agents)
        self.assertIn("sandbox intentionally cannot open network sockets", agents)
        self.assertIn("server runs the canonical linter", agents)


if __name__ == "__main__":
    unittest.main()

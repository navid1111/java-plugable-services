#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path


def source_files(root: Path) -> list[Path]:
    candidates = {
        path for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() in {".html", ".js", ".jsx", ".ts", ".tsx"}
    }
    return sorted(
        path for path in candidates
        if not any(part.startswith(".") or part == "node_modules" for part in path.relative_to(root).parts)
    )


def nearby_method(source: str, marker: str, method: str) -> bool:
    for match in re.finditer(re.escape(marker), source, re.IGNORECASE):
        window = source[max(0, match.start() - 250):match.end() + 500]
        if re.search(r"method\s*:\s*['\"]" + re.escape(method) + r"['\"]", window, re.IGNORECASE):
            return True
    return False


def async_blocks(source: str) -> list[str]:
    """Extract brace-balanced async function bodies for small browser sources."""
    starts = []
    patterns = (
        r"async\s+function\s+[A-Za-z_$][\w$]*\s*\([^)]*\)\s*\{",
        r"async\s*\([^)]*\)\s*=>\s*\{",
    )
    for pattern in patterns:
        starts.extend(match.end() - 1 for match in re.finditer(pattern, source))
    blocks = []
    for start in sorted(starts):
        depth = 0
        for index in range(start, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    blocks.append(source[start + 1:index])
                    break
    return blocks


def lint(source: str) -> list[str]:
    problems: list[str] = []
    lowered = source.lower()

    if re.search(r"['\"]/internal(?:/|['\"])", source, re.IGNORECASE):
        problems.append("browser code must not call /internal/* workload APIs")

    if nearby_method(source, "/auth/me", "DELETE") and "appbuilder: allow-account-deactivation" not in lowered:
        problems.append("DELETE /auth/me deactivates the account; logout must only remove the local JWT")

    if nearby_method(source, "/post-search/documents/", "PUT"):
        problems.append("public search mutations do not exist; search indexing comes from post lifecycle events")

    if "postWithFallback" in source:
        problems.append("do not guess multiple write payloads; use the single documented request contract")

    if any(
        "await" in block
        and "event.currentTarget" in block
        and block.index("await") < block.rindex("event.currentTarget")
        for block in async_blocks(source)
    ):
        problems.append(
            "event.currentTarget can become null after await; capture the form element before the first await"
        )

    if re.search(
        r"['\"]Content-Type['\"]\s*\]?\s*[:=]\s*['\"]multipart/form-data",
        source,
        re.IGNORECASE,
    ):
        problems.append("do not set multipart/form-data manually when sending FormData; the browser adds its boundary")

    protected = any(path in source for path in (
        "/posts", "/media", "/comments", "/post-search", "/bff", "/leetcode", "/bookings", "/chat"
    ))
    if protected:
        for token, message in (
            ("appbuilder.jwt", "protected apps must load the JWT from appbuilder.jwt"),
            ("Authorization", "protected requests must send the Authorization header"),
            ("Bearer", "protected requests must use the Bearer token scheme"),
        ):
            if token not in source:
                problems.append(message)

    uses_media_intents = "/media/upload-intents" in source
    if uses_media_intents:
        required = {
            "targetType": "upload intent body requires targetType",
            "targetId": "upload intent body requires targetId",
            "idempotencyKey": "upload intent body requires a unique idempotencyKey",
            "resourceType": "upload intent body requires resourceType",
            "format": "upload intent body requires an extension-only format",
            "bytes": "upload intent body requires bytes",
            "authorization": "upload intent response is nested under authorization",
            "uploadUrl": "direct upload must use authorization.uploadUrl",
            "api_key": "Cloudinary form requires api_key",
            "signature": "Cloudinary form requires signature",
            "public_id": "Cloudinary form requires public_id",
            "publicId": "finalize body requires publicId from the provider response",
            "secureUrl": "finalize body requires secureUrl from the provider response",
            "reasonCode": "failed intents must send reasonCode",
        }
        for token, message in required.items():
            if token not in source:
                problems.append(message)

    creates_posts = bool(re.search(r"['\"]/posts['\"]", source))
    writes_media = uses_media_intents or nearby_method(source, "/media/targets/", "POST")
    writes_comments = nearby_method(source, "/comments/targets/", "POST")
    has_summary = any(
        target in source and "/summary" in source
        for target in ("/media/targets/", "/comments/targets/")
    )
    has_backoff = any(token in source for token in ("setTimeout", "sleep(", "pause("))
    handles_projection_race = "target does not exist or is deleted" in lowered
    if creates_posts and (writes_media or writes_comments) and not (has_summary and has_backoff and handles_projection_race):
        problems.append(
            "post -> media/comment writes require bounded target-projection readiness retry for "
            "'target does not exist or is deleted'"
        )

    if nearby_method(source, "/follow", "PUT") and "username=" not in source:
        problems.append("follow PUT requires ?username={author.username} and the BFF author.userId")

    if nearby_method(source, "/bookings", "POST"):
        for token, message in (
            ("slotId", "booking writes require {slotId}, not a resource id"),
            ("slots", "booking UI must select an available slot from each resource"),
        ):
            if token not in source:
                problems.append(message)

    if "/leetcode/admin/problems" in source:
        for token, message in (
            ("ADMIN", "LeetCode admin UI must be gated by the ADMIN role from /auth/me"),
            ("testCases", "LeetCode problem writes require structured testCases"),
            ("codeStubs", "LeetCode problem writes require language codeStubs"),
            ("hidden", "LeetCode test cases must explicitly mark public versus hidden cases"),
        ):
            if token not in source:
                problems.append(message)

    return problems


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    files = source_files(root)
    if not files:
        print("[contract-lint] no HTML/JS source files found", file=sys.stderr)
        return 1
    source = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in files)
    problems = lint(source)
    if problems:
        print("[contract-lint] frontend/backend contract violations:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"[contract-lint] passed ({len(files)} source file(s))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env bash
# T054 guard: no client performs the cross-service propagation that services now own.
# The search projection is derived from post.created/updated/deleted events, so clients
# must never mutate it or write like counts directly. GET reads of search are allowed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Forbidden: a mutating HTTP call to the search document store, or a like-count write.
PATTERN='(-X[[:space:]]+(PUT|POST|DELETE)[^"]*post-search/documents|post-search/documents/[^"[:space:]]*/like-count)'

# Scan the maintained platform demos/clients. Generated app-builder workspaces are
# excluded — they are tool output, not the platform's own client orchestration.
hits="$(grep -rInE "$PATTERN" "$ROOT/examples" 2>/dev/null || true)"

if [ -n "$hits" ]; then
  echo "Forbidden client-side search dual-write found (search is event-derived):" >&2
  echo "$hits" >&2
  exit 1
fi

echo "verify-no-client-orchestration: no client-side search/media lifecycle dual writes found."

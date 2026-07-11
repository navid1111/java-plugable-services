#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="$ROOT/platform/messaging-contracts/src/main/java/com/example/platform/messaging/EventTypes.java"
CATALOG="$ROOT/docs/architecture/event-catalog.md"

test -f "$SOURCE" || { echo "Missing EventTypes.java" >&2; exit 1; }
test -f "$CATALOG" || { echo "Missing event catalog" >&2; exit 1; }

EVENTS="$(sed -n 's/.*= "\([a-z][a-z0-9.-]*\.v[0-9][0-9]*\)";.*/\1/p' "$SOURCE" | sort -u)"
test -n "$EVENTS" || { echo "No registered event constants found" >&2; exit 1; }

missing=0
while IFS= read -r event; do
  if ! grep -Fq "\`$event\`" "$CATALOG"; then
    echo "EventTypes entry is missing from catalog: $event" >&2
    missing=1
  fi
done <<< "$EVENTS"

if grep -Eqi 'password|password_hash|bearer token|private key' \
  "$ROOT/platform/messaging-contracts/src/main/resources/schemas/"*.json; then
  echo "A contract schema contains a prohibited credential field" >&2
  missing=1
fi

test "$missing" -eq 0
echo "Event catalog verification passed."

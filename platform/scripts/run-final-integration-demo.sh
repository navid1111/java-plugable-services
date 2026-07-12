#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

echo "==> End-to-end post lifecycle demo"
./platform/scripts/test-post-lifecycle-e2e.sh --clean

modules=(
  platform/messaging-contracts
  platform/messaging-support
  auth-service
  tweeter-service
  comment-service
  media-service
  post-search-service
  bff
  booking-service
  whatsapp-service
  leetcode-service
)

for module in "${modules[@]}"; do
  echo "==> Test suite: ${module}"
  mvn -q -f "${module}/pom.xml" test
done

echo "Final integration demo and all platform service test suites passed."

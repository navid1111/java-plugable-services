#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

echo "Running the production-like stable-identity backfill and rollback drill..."
mvn -f tweeter-service/pom.xml \
  -Dtest=PostLifecycleMigrationTest test
mvn -f platform/messaging-support/pom.xml \
  -Dtest=IdentityBackfillProductionLikeTest test

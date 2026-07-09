#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
$DIR/../leetcode-service/plug/kong-setup.sh "${KONG_ADMIN_URL:-http://localhost:18001}"

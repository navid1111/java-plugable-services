#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
$DIR/../leetcode-service/plug/kong-setup.sh http://localhost:8001

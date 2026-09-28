#!/usr/bin/env bash
# Tail logs for the core stack (or a single service passed as $1).
#   bash infra/scripts/ec2-logs.sh            # all services
#   bash infra/scripts/ec2-logs.sh gateway    # one service
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT/infra/docker"

docker compose --env-file ../../.env logs -f --tail=100 "${1:-}"

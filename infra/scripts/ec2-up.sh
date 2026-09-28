#!/usr/bin/env bash
# Bring up the AegisAI core stack on the EC2. Run from the repo root on the EC2.
#   bash infra/scripts/ec2-up.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

if [[ ! -f .env ]]; then
  echo "ERROR: .env not found. Copy .env.example to .env and fill it in first."
  exit 1
fi

cd infra/docker
echo "Building and starting core stack (postgres, redis, ml-service, gateway, frontend)..."
docker compose --env-file ../../.env up -d --build

echo
echo "Waiting for health... (this can take a minute on first build)"
sleep 5
docker compose --env-file ../../.env ps

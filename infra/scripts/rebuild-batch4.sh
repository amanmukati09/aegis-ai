#!/usr/bin/env bash
# Extract latest tarball and rebuild ml + gateway + frontend on EC2 (Batch 4).
set -uo pipefail
cd ~ || exit 1
tar -xzf ~/aegisai.tgz
cd ~/aegisai/infra/docker || exit 1
nohup docker compose --env-file ../../.env up -d --build ml-service gateway frontend \
  > ~/aegisai/build-batch4.log 2>&1 &
echo "rebuild kicked off (pid $!)"

#!/usr/bin/env bash
# Extract latest tarball and rebuild gateway + frontend on EC2 (Batch 5, no ML change).
set -uo pipefail
cd ~ || exit 1
tar -xzf ~/aegisai.tgz
cd ~/aegisai/infra/docker || exit 1
nohup docker compose --env-file ../../.env up -d --build gateway frontend \
  > ~/aegisai/build-batch5.log 2>&1 &
echo "rebuild kicked off (pid $!)"

#!/usr/bin/env bash
# Add a 4GB swap file. Recommended on the 3.8GB EC2 (no swap by default) so the
# Docker build + core stack have breathing room. Idempotent and reversible.
#
# Run once on the EC2:  sudo bash infra/scripts/setup-swap.sh
set -euo pipefail

SWAPFILE=/swapfile
SIZE=4G

if swapon --show | grep -q "$SWAPFILE"; then
  echo "Swap already active:"
  swapon --show
  exit 0
fi

echo "Creating ${SIZE} swap at ${SWAPFILE} ..."
fallocate -l "$SIZE" "$SWAPFILE" || dd if=/dev/zero of="$SWAPFILE" bs=1M count=4096
chmod 600 "$SWAPFILE"
mkswap "$SWAPFILE"
swapon "$SWAPFILE"

if ! grep -q "$SWAPFILE" /etc/fstab; then
  echo "$SWAPFILE none swap sw 0 0" >> /etc/fstab
  echo "Added to /etc/fstab (persists across reboots)."
fi

echo "Done. Current swap:"
swapon --show
free -h

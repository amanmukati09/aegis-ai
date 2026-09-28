#!/usr/bin/env bash
# Batch 6 verify: Track C dormant status (gateway + ML), core stays healthy.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="b6+$(date +%s)@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"B6\",\"organizationName\":\"B6Org $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $TOKEN"

echo "=== gateway Track C status (expect mode: core, all active:false) ==="
curl -s "$BASE/api/trackc/status" -H "$AUTH"
echo

echo "=== ML heavy-model status (expect mode: core, available:false) ==="
curl -s http://localhost:8001/v1/heavy/status
echo

echo "=== core still healthy ==="
curl -s "$BASE/actuator/health"; echo
curl -s http://localhost:8001/healthz; echo

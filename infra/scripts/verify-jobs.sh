#!/usr/bin/env bash
# Phase 7 verification: async bulk-analyze job (submit -> poll -> completed) + live state.
# Run on the EC2: bash infra/scripts/verify-jobs.sh
set -uo pipefail

BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="job+$(date +%s)@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Job\",\"organizationName\":\"JobOrg $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

echo "=== submit bulk-analyze job ==="
JID=$(curl -s -X POST "$BASE/api/jobs/bulk-analyze" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"logs":["ERROR db connection refused","WARN pool 95%","ERROR 503 rate 62%"]}' | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "job id: $JID"

echo "=== poll (up to ~15s) ==="
for i in $(seq 1 10); do
  R=$(curl -s "$BASE/api/jobs/$JID" -H "Authorization: Bearer $TOKEN")
  ST=$(echo "$R" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
  echo "  status: $ST"
  if [ "$ST" = "completed" ] || [ "$ST" = "failed" ]; then echo "$R" | head -c 500; break; fi
  sleep 1.5
done

echo; echo; echo "=== live state ==="
curl -s "$BASE/api/live/state" -H "Authorization: Bearer $TOKEN" | head -c 300
echo

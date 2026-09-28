#!/usr/bin/env bash
# Phase 6 verification: RCA tree, code-fix, NL->SQL analytics, timeseries, dependency graph.
# Run on the EC2: bash infra/scripts/verify-advanced.sh
set -uo pipefail

BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="adv+$(date +%s)@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Adv\",\"organizationName\":\"AdvOrg $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

echo "=== seed incidents via diagnosis ==="
curl -s -X POST "$BASE/api/diagnose" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"logs":["ERROR database connection refused to db:5432","WARN api-gateway upstream timeout"]}' | grep -o '"incidentId":"[^"]*"'
IID=$(curl -s "$BASE/api/incidents" -H "Authorization: Bearer $TOKEN" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "incident: $IID"

echo; echo "=== RCA tree ==="
curl -s -X POST "$BASE/api/incidents/$IID/rca-tree" -H "Authorization: Bearer $TOKEN" | head -c 500

echo; echo; echo "=== code fix ==="
curl -s -X POST "$BASE/api/incidents/$IID/code-fix" -H "Authorization: Bearer $TOKEN" | head -c 400

echo; echo; echo "=== NL->SQL analytics (incidents by severity) ==="
curl -s -X POST "$BASE/api/analytics/ask" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"question":"How many incidents by severity?"}'

echo; echo; echo "=== analytics timeseries (14d) ==="
curl -s "$BASE/api/analytics/timeseries" -H "Authorization: Bearer $TOKEN" | head -c 300

echo; echo; echo "=== dependency graph ==="
curl -s "$BASE/api/dependency/graph" -H "Authorization: Bearer $TOKEN" | head -c 400
echo

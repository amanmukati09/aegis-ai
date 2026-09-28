#!/usr/bin/env bash
# Phase 2 verification: incident CRUD + dashboard summary + cross-org isolation.
# Run on the EC2: bash infra/scripts/verify-incidents.sh
set -uo pipefail

BASE=http://localhost:8080
JSON='Content-Type: application/json'

reg() {
  local org="$1" email="admin+$(date +%s%N)@example.com"
  curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
    -d "{\"email\":\"$email\",\"password\":\"password123\",\"fullName\":\"Admin\",\"organizationName\":\"$org\"}" \
    | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4
}

echo "=== register org A + org B ==="
TA=$(reg "OrgA-$(date +%s)")
TB=$(reg "OrgB-$(date +%s)")
echo "tokenA len: ${#TA}, tokenB len: ${#TB}"

echo
echo "=== A creates incident ==="
CREATE=$(curl -s -X POST "$BASE/api/incidents" -H "Authorization: Bearer $TA" -H "$JSON" \
  -d '{"title":"API latency spike","severity":"high","anomalyDescription":"disk full"}')
echo "$CREATE"
IID=$(echo "$CREATE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo
echo "=== A lists incidents ==="
curl -s "$BASE/api/incidents" -H "Authorization: Bearer $TA"

echo
echo
echo "=== B reads A's incident (expect 404) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "$BASE/api/incidents/$IID" -H "Authorization: Bearer $TB"

echo "=== B deletes A's incident (expect 404) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X DELETE "$BASE/api/incidents/$IID" -H "Authorization: Bearer $TB"

echo "=== B lists (expect empty items) ==="
curl -s "$BASE/api/incidents" -H "Authorization: Bearer $TB"

echo
echo
echo "=== A resolves incident ==="
curl -s -X POST "$BASE/api/incidents/$IID/resolve" -H "Authorization: Bearer $TA" -H "$JSON" \
  -d '{"resolutionNotes":"restarted service"}'

echo
echo
echo "=== A dashboard summary ==="
curl -s "$BASE/api/dashboard/summary" -H "Authorization: Bearer $TA"

echo
echo
echo "=== A CSV export (first lines) ==="
curl -s "$BASE/api/incidents/export/csv" -H "Authorization: Bearer $TA" | head -3

echo
echo "=== A deletes own incident (expect 204) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X DELETE "$BASE/api/incidents/$IID" -H "Authorization: Bearer $TA"

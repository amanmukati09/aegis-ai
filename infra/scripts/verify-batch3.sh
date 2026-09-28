#!/usr/bin/env bash
# Batch 3 verify: embed, similar incidents (pgvector), timeline, blast radius.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="b3+$(date +%s)@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"B3\",\"organizationName\":\"B3Org $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

echo "=== ml embed direct (expect 384-dim) ==="
curl -s -X POST http://localhost:8001/v1/embed -H "$JSON" -d '{"text":"database connection refused"}' \
  | grep -o '"dim":[0-9]*'
echo

echo "=== create 3 incidents (2 db-related, 1 network) ==="
curl -s -X POST "$BASE/api/incidents" -H "Authorization: Bearer $TOKEN" -H "$JSON" -d '{"title":"DB connection refused","severity":"critical","anomalyDescription":"database connection pool exhausted 5432"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "Authorization: Bearer $TOKEN" -H "$JSON" -d '{"title":"Database timeout","severity":"high","anomalyDescription":"database queries timing out slow connection"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "Authorization: Bearer $TOKEN" -H "$JSON" -d '{"title":"Network partition","severity":"medium","anomalyDescription":"network dns resolution failing"}' > /dev/null
sleep 1

# Target a DB incident (not the newest) so a similar match with a real score exists.
IID=$(curl -s "$BASE/api/incidents" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;items=json.load(sys.stdin)["items"];print(next(i["id"] for i in items if "DB connection" in i["title"]))')
echo "target incident (DB connection refused): $IID"

echo "=== similar incidents (expect 'Database timeout' with score ~0.42) ==="
curl -s "$BASE/api/incidents/$IID/similar" -H "Authorization: Bearer $TOKEN"

echo; echo "=== timeline ==="
curl -s "$BASE/api/incidents/$IID/timeline" -H "Authorization: Bearer $TOKEN"

echo; echo "=== blast radius for 'database' ==="
curl -s "$BASE/api/dependency/blast-radius/database" -H "Authorization: Bearer $TOKEN"
echo

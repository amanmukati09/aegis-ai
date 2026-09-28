#!/usr/bin/env bash
# Batch 4 verify: chat streaming (SSE), chat search, sentiment, RL triage.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="b4+$(date +%s)@example.com"
ORG="B4Org $(date +%s)"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"B4\",\"organizationName\":\"$ORG\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $TOKEN"

echo "=== ML sentiment direct (expect high urgency) ==="
curl -s -X POST http://localhost:8001/v1/sentiment -H "$JSON" \
  -d '{"text":"URGENT the database is DOWN this is critical!!"}'
echo

echo "=== gateway sentiment proxy (expect positive) ==="
curl -s -X POST "$BASE/api/chat/sentiment" -H "$AUTH" -H "$JSON" \
  -d '{"text":"thanks, the issue is resolved and working great"}'
echo

echo "=== chat stream (SSE, expect event: session + tokens + [DONE]) ==="
curl -s -N -X POST "$BASE/api/chat/stream" -H "$AUTH" -H "$JSON" \
  -d '{"message":"In one short sentence, what is a good first step for a database connection error?"}' \
  | head -c 600
echo
echo "--- (stream end) ---"

echo "=== wait for persistence then search ==="
sleep 2
echo "--- search 'database' (expect >=1 hit) ---"
curl -s "$BASE/api/chat/search?q=database" -H "$AUTH"
echo

echo "=== RL triage: seed incidents ==="
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"DB pool exhausted","severity":"critical","anomalyDescription":"database connection pool exhausted"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"Slow API","severity":"medium","anomalyDescription":"api gateway latency high"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"Cache miss storm","severity":"high","anomalyDescription":"redis cache eviction spike"}' > /dev/null
sleep 1

echo "--- triage queue (expect ranked, critical highest priority) ---"
curl -s "$BASE/api/triage/queue" -H "$AUTH"
echo

echo "--- triage train (no resolved yet -> trained_on 0 is OK) ---"
curl -s -X POST "$BASE/api/triage/train" -H "$AUTH" -H "$JSON" -d '{}'
echo

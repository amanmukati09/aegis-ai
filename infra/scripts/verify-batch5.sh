#!/usr/bin/env bash
# Batch 5 verify: health score, benchmark, predictions, clustering (all gateway-computed).
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="b5+$(date +%s)@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"B5\",\"organizationName\":\"B5Org $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $TOKEN"

echo "=== seed incidents (mix of components/severities) ==="
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"DB pool exhausted","severity":"critical","anomalyDescription":"database connection pool exhausted, sql timeout"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"DB slow queries","severity":"high","anomalyDescription":"database queries slow, sql connection latency"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"Nginx crash","severity":"high","anomalyDescription":"nginx worker crashed, service restart"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"OOM killed","severity":"critical","anomalyDescription":"out of memory oom killer terminated process"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"Redis eviction","severity":"medium","anomalyDescription":"redis cache eviction spike, memory pressure"}' > /dev/null
sleep 1

echo "=== health score (expect score dropping from 100 due to open critical) ==="
curl -s "$BASE/api/insights/health-score" -H "$AUTH"
echo

echo "=== benchmark (expect totalIncidents 5, rates present) ==="
curl -s "$BASE/api/insights/benchmark" -H "$AUTH"
echo

echo "=== predictions (expect component_risk + severity_trend, riskLevel) ==="
curl -s "$BASE/api/insights/predictions" -H "$AUTH"
echo

echo "=== clusters (expect DB incidents grouped) ==="
curl -s "$BASE/api/insights/clusters" -H "$AUTH"
echo

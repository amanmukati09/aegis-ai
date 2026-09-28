#!/usr/bin/env bash
# Confirm the RL agent actually learns: resolve an incident, train, expect q_states>0.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="tl+$(date +%s)@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"TL\",\"organizationName\":\"TLOrg $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $TOKEN"

# create + resolve a critical DB incident
IID=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" \
  -d '{"title":"DB down","severity":"critical","anomalyDescription":"database connection refused"}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "created + resolving incident $IID"
curl -s -X POST "$BASE/api/incidents/$IID/resolve" -H "$AUTH" -H "$JSON" \
  -d '{"resolutionNotes":"restarted pool"}' > /dev/null

echo "=== train (expect trained_on >=1, q_states >=1) ==="
curl -s -X POST "$BASE/api/triage/train" -H "$AUTH" -H "$JSON" -d '{}'
echo
echo "=== ML triage stats ==="
curl -s http://localhost:8001/v1/triage/stats
echo

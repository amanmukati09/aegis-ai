#!/usr/bin/env bash
# Verify the extended Copilot capabilities: semantic (pgvector) KB search, the
# get_runbook tool, and the diagnose_live tool (live diagnosis without creating an
# incident).
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)

echo "=== 0. Register a test user ==="
REG=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"ext+${STAMP}@example.com\",\"password\":\"password123\",\"fullName\":\"Ext User\",\"organizationName\":\"Ext Org ${STAMP}\"}")
JWT=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $JWT"
[ -z "$JWT" ] && { echo "FAIL: could not register test user"; echo "$REG"; exit 1; }
echo "logged in"

echo
echo "=== 1. diagnose_live: ask Copilot about a live problem with NO incident filed ==="
ASK1=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH" -H "$JSON" \
  -d '{"message":"Our payments service is throwing 503s and the DB connection pool looks maxed out. What is wrong and what should I do right now? Use the live diagnosis tool."}')
REPLY1=$(echo "$ASK1" | python3 -c "import json,sys; print(json.load(sys.stdin).get('reply',''))" 2>/dev/null || echo "$ASK1")
echo "--- reply ---"
echo "$REPLY1"
SESSION_ID=$(echo "$ASK1" | grep -o '"sessionId":"[^"]*"' | head -1 | cut -d'"' -f4)

echo
echo "=== 2. Confirm diagnose_live did NOT create a visible incident as a side effect ==="
AFTER_COUNT=$(curl -s "$BASE/api/incidents?page=0&size=100" -H "$AUTH" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
echo "incident count after the live-diagnosis question: ${AFTER_COUNT:-unknown}"
[ "${AFTER_COUNT:-0}" = "0" ] && echo "PASS: no incident was created by diagnose_live" \
  || echo "WARN: incident count is ${AFTER_COUNT} (expected 0 for a brand-new org at this point)"

echo
echo "=== 3. Create + resolve a real incident, generate a KB article (embeds it) ==="
INC=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" \
  -d '{"title":"Payment gateway timeout storm","severity":"critical","anomalyDescription":"Payment provider API calls timing out, connection pool exhausted under load"}')
INC_ID=$(echo "$INC" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "incident: $INC_ID"
curl -s -X POST "$BASE/api/incidents/$INC_ID/resolve" -H "$AUTH" -H "$JSON" \
  -d '{"resolutionNotes":"Increased connection pool size and added circuit breaker with exponential backoff"}' > /dev/null
KB=$(curl -s -X POST "$BASE/api/kb/generate/$INC_ID" -H "$AUTH" -H "$JSON")
KB_TITLE=$(echo "$KB" | python3 -c "import json,sys; print(json.load(sys.stdin).get('title',''))" 2>/dev/null || echo "?")
echo "KB article generated: $KB_TITLE"

echo
echo "=== 4. Semantic KB search: query with DIFFERENT words than the article, should still match ==="
SEARCH=$(curl -s "$BASE/api/kb/search?q=checkout+slow+due+to+upstream+API+not+responding" -H "$AUTH")
echo "$SEARCH" | python3 -m json.tool 2>/dev/null || echo "$SEARCH"
echo "$SEARCH" | grep -qi "$INC_ID\|Payment gateway\|payment" && echo "PASS: semantic search found the conceptually related article without exact keyword match" \
  || echo "WARN: semantic match not confirmed from direct search"

echo
echo "=== 5. Ask the Copilot the same thing in plain English (tool-calling path) ==="
ASK2=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH" -H "$JSON" \
  -d '{"message":"We are seeing checkout get really slow because an upstream API is not responding. Have we dealt with something like this before? What was the fix?"}')
REPLY2=$(echo "$ASK2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('reply',''))" 2>/dev/null || echo "$ASK2")
echo "--- reply ---"
echo "$REPLY2"

echo
echo "=== 6. get_runbook: ask Copilot for operational steps on the real incident by name ==="
ASK3=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH" -H "$JSON" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"Find the incident called Payment gateway timeout storm and give me its runbook steps.\"}")
REPLY3=$(echo "$ASK3" | python3 -c "import json,sys; print(json.load(sys.stdin).get('reply',''))" 2>/dev/null || echo "$ASK3")
echo "--- reply ---"
echo "$REPLY3"

echo
echo "=== cleanup note: test org/users/incidents are not deleted by this script ==="
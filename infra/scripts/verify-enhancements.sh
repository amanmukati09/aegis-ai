#!/usr/bin/env bash
# Verify the three enhancements: dependency health/critical-paths, knowledge base, chat windowing.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)
EMAIL="enh+${STAMP}@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Enh\",\"organizationName\":\"Enh $STAMP\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $TOKEN"

echo "=== seed incidents for dependency graph ==="
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"DB outage","severity":"critical","anomalyDescription":"database connection pool exhausted, api-gateway 503s"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"DB slow","severity":"high","anomalyDescription":"database queries slow, api-gateway timeouts"}' > /dev/null
curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d '{"title":"Cache miss","severity":"medium","anomalyDescription":"cache eviction, database load increased"}' > /dev/null
sleep 1

echo "=== dependency graph (expect healthScore + degree per node, criticalPaths[]) ==="
curl -s "$BASE/api/dependency/graph" -H "$AUTH" | python3 -m json.tool | head -40

echo
echo "=== blast radius for database (expect totalIncidentImpact + severity) ==="
curl -s "$BASE/api/dependency/blast-radius/database" -H "$AUTH"
echo

echo
echo "=== knowledge base: create + resolve an incident, generate article ==="
IID=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" \
  -d '{"title":"Redis eviction storm","severity":"high","anomalyDescription":"redis maxmemory evicting keys under load"}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -X POST "$BASE/api/incidents/$IID/resolve" -H "$AUTH" -H "$JSON" -d '{"resolutionNotes":"Increased maxmemory, tuned eviction policy"}' > /dev/null

echo "--- generate article for resolved incident ---"
curl -s -X POST "$BASE/api/kb/generate/$IID" -H "$AUTH" -H "$JSON" -d '{}'
echo

echo "--- list articles (expect 1) ---"
curl -s "$BASE/api/kb/articles" -H "$AUTH" | python3 -c "import sys,json; d=json.load(sys.stdin); print('count:', len(d)); [print(' -', a['title'], '|', a['difficulty']) for a in d]"

echo "--- search kb for 'redis' ---"
curl -s "$BASE/api/kb/search?q=redis" -H "$AUTH"
echo

echo "--- idempotency: generating again for same incident should 400 ---"
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/kb/generate/$IID" -H "$AUTH" -H "$JSON" -d '{}'

echo
echo "=== chat memory windowing: send several messages, confirm stream still works ==="
S1=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH" -H "$JSON" -d '{"message":"hello, remember the number 42"}')
SID=$(echo "$S1" | grep -o '"sessionId":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "session: $SID"
for i in 1 2 3; do
  curl -s -X POST "$BASE/api/chat/message" -H "$AUTH" -H "$JSON" -d "{\"sessionId\":\"$SID\",\"message\":\"ping $i\"}" > /dev/null
done
echo "--- stream one more turn (expect session+tokens+DONE, no error) ---"
curl -s -N -X POST "$BASE/api/chat/stream" -H "$AUTH" -H "$JSON" -d "{\"sessionId\":\"$SID\",\"message\":\"say ok\"}" | tr -d '\r' | head -c 300
echo
echo "--- message count in session (expect 9: 4 sent pairs... ) ---"
curl -s "$BASE/api/chat/sessions/$SID/messages" -H "$AUTH" | python3 -c "import sys,json; print('messages:', len(json.load(sys.stdin)))"

#!/usr/bin/env bash
# Full end-to-end smoke across all batches. One fresh account, one pass.
set -uo pipefail
BASE=http://localhost:8080
ML=http://localhost:8001
JSON='Content-Type: application/json'
STAMP=$(date +%s)
EMAIL="smoke+$STAMP@example.com"
PASS=0; FAIL=0

ok()   { echo "  PASS: $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }
has()  { echo "$1" | grep -q "$2" && ok "$3" || bad "$3 :: $1"; }

echo "== health =="
has "$(curl -s $BASE/actuator/health)" '"status":"UP"' "gateway health"
has "$(curl -s $ML/healthz)" '"status":"ok"' "ml health"

echo "== auth (register) =="
REG=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Smoke\",\"organizationName\":\"Smoke $STAMP\"}")
TOKEN=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $TOKEN"
[ -n "$TOKEN" ] && ok "register+token" || { bad "register"; echo "$REG"; exit 1; }

echo "== incidents (create x3) =="
for d in \
  '{"title":"DB pool exhausted","severity":"critical","anomalyDescription":"database connection pool exhausted sql timeout"}' \
  '{"title":"DB slow queries","severity":"high","anomalyDescription":"database queries slow sql latency"}' \
  '{"title":"OOM killed","severity":"critical","anomalyDescription":"out of memory oom killer"}'; do
  curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" -d "$d" > /dev/null
done
sleep 1
LIST=$(curl -s "$BASE/api/incidents" -H "$AUTH")
has "$LIST" '"items"' "incident list"
IID=$(echo "$LIST" | python3 -c 'import sys,json;items=json.load(sys.stdin)["items"];print(next(i["id"] for i in items if "DB pool" in i["title"]))')

echo "== batch2: ingest URL (SSRF block) + PDF + runbook =="
has "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/ingest/from-url" -H "$AUTH" -H "$JSON" -d '{"url":"http://169.254.169.254/latest/meta-data/"}')" '400' "ingest SSRF blocked"
has "$(curl -s -X POST "$BASE/api/incidents/$IID/runbook" -H "$AUTH" -H "$JSON" -d '{}' | head -c 400)" '.' "runbook returns"
PDF=$(curl -s -X POST "$BASE/api/incidents/$IID/report.pdf" -H "$AUTH" -H "$JSON" -d '{}' | head -c 5)
echo "$PDF" | grep -q 'PDF' && ok "incident PDF (%PDF)" || bad "incident PDF"

echo "== batch3: timeline + similar + blast-radius =="
has "$(curl -s "$BASE/api/incidents/$IID/timeline" -H "$AUTH")" 'detected' "timeline"
has "$(curl -s "$BASE/api/incidents/$IID/similar" -H "$AUTH")" 'score' "similar incidents"
has "$(curl -s "$BASE/api/dependency/blast-radius/database" -H "$AUTH")" 'radius' "blast radius"

echo "== batch4: chat stream + search + sentiment + triage =="
STREAM=$(curl -s -N -X POST "$BASE/api/chat/stream" -H "$AUTH" -H "$JSON" \
  -d '{"message":"one word: ok?"}' | tr -d '\r')
has "$STREAM" 'event:session' "chat stream session event"
has "$STREAM" '\[DONE\]' "chat stream DONE"
sleep 1
has "$(curl -s "$BASE/api/chat/search?q=ok" -H "$AUTH")" 'sessionId' "chat search"
has "$(curl -s -X POST "$BASE/api/chat/sentiment" -H "$AUTH" -H "$JSON" -d '{"text":"URGENT down critical!!"}')" '"urgency":"high"' "sentiment high"
has "$(curl -s "$BASE/api/triage/queue" -H "$AUTH")" '"priority":5' "triage ranks critical P5"

echo "== batch5: insights =="
has "$(curl -s "$BASE/api/insights/health-score" -H "$AUTH")" 'score' "health score"
has "$(curl -s "$BASE/api/insights/benchmark" -H "$AUTH")" 'totalIncidents' "benchmark"
has "$(curl -s "$BASE/api/insights/predictions" -H "$AUTH")" 'riskLevel' "predictions"
has "$(curl -s "$BASE/api/insights/clusters" -H "$AUTH")" 'clusters' "clusters"

echo "== batch6: track C dormant =="
has "$(curl -s "$BASE/api/trackc/status" -H "$AUTH")" '"mode":"core"' "trackc core mode"
has "$(curl -s $ML/v1/heavy/status)" '"available":false' "heavy models dormant"

echo
echo "==================================="
echo "  SMOKE RESULT: $PASS passed, $FAIL failed"
echo "==================================="
[ "$FAIL" -eq 0 ]

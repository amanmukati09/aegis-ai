#!/usr/bin/env bash
# Verify the new bulk-analysis -> N distinct incidents -> created -> combined PDF flow.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)
EMAIL="bulkinc+${STAMP}@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"BulkInc\",\"organizationName\":\"BulkInc $STAMP\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $TOKEN"

echo "=== ML segmenter direct (database-outage.log) ==="
python3 - "$HOME/aegisai/samples/logs/database-outage.log" <<'PYEOF'
import json, sys, urllib.request
path = sys.argv[1]
lines = [l.rstrip("\n") for l in open(path, encoding="utf-8") if l.strip()]
req = urllib.request.Request(
    "http://localhost:8001/v1/analyze/bulk-incidents",
    data=json.dumps({"lines": lines, "max_incidents": 25}).encode(),
    headers={"Content-Type": "application/json"},
)
resp = json.load(urllib.request.urlopen(req, timeout=30))
print("incident_count:", resp.get("incident_count"))
print("severity_breakdown:", resp.get("severity_breakdown"))
print("top_components:", resp.get("top_components"))
for inc in resp.get("incidents", []):
    print(" -", inc["title"], "|", inc["severity"], "|", inc["component"], "| count=", inc["count"])
PYEOF

echo
echo "=== gateway bulk-analyze job (same file, full pipeline incl. incident creation) ==="
LINES_JSON=$(python3 -c "
import json
lines = [l.rstrip('\n') for l in open('$HOME/aegisai/samples/logs/database-outage.log', encoding='utf-8') if l.strip()]
print(json.dumps(lines))
")
JOB=$(curl -s -X POST "$BASE/api/jobs/bulk-analyze" -H "$AUTH" -H "$JSON" \
  -d "{\"logs\":$LINES_JSON,\"source\":\"database-outage.log\",\"createIncidents\":true}")
JOB_ID=$(echo "$JOB" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "job id: $JOB_ID"

echo "waiting for job to complete..."
for i in $(seq 1 20); do
  sleep 1
  STATUS=$(curl -s "$BASE/api/jobs/$JOB_ID" -H "$AUTH")
  ST=$(echo "$STATUS" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
  if [ "$ST" = "completed" ] || [ "$ST" = "failed" ]; then break; fi
done
echo "final status: $ST"
echo "$STATUS" | python3 -c "
import json, sys
d = json.load(sys.stdin)
r = d.get('result') or {}
print('incident_count:', r.get('incident_count'))
print('created_count:', r.get('created_count'))
for c in (r.get('created') or [])[:20]:
    print(' -', c.get('title'), '|', c.get('severity'), '|', c.get('status'))
if d.get('error'):
    print('ERROR:', d['error'])
"

echo
echo "=== confirm created incidents show up in /api/incidents ==="
curl -s "$BASE/api/incidents?size=20" -H "$AUTH" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print('total incidents in list:', d.get('totalElements'))
for i in d.get('items', []):
    print(' -', i['title'], '|', i['severity'], '|', i['status'])
"

echo
echo "=== download combined PDF for the job ==="
curl -s -o /tmp/bulk-report.pdf -D - "$BASE/api/jobs/$JOB_ID/report.pdf" -H "$AUTH" | head -n 5
echo "--- file check ---"
head -c 5 /tmp/bulk-report.pdf; echo
ls -la /tmp/bulk-report.pdf

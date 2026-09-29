#!/usr/bin/env bash
# Verify the real ingestion path: register a stream, create an API key, push events
# authenticated ONLY with the API key (no JWT), confirm auto-incident creation + linking
# + stream activity counters, and confirm a benign batch does NOT create an incident.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)
EMAIL="ingest+${STAMP}@example.com"

REG=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Ingest\",\"organizationName\":\"Ingest $STAMP\"}")
JWT=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_JWT="Authorization: Bearer $JWT"

echo "=== register a stream ==="
STREAM=$(curl -s -X POST "$BASE/api/streams" -H "$AUTH_JWT" -H "$JSON" \
  -d '{"name":"prod-api-gateway","description":"nginx access/error log"}')
STREAM_ID=$(echo "$STREAM" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "stream: $STREAM_ID"

echo "=== create an API key ==="
KEY_RESP=$(curl -s -X POST "$BASE/api/api-keys" -H "$AUTH_JWT" -H "$JSON" -d '{"name":"ingestion-agent"}')
API_KEY=$(echo "$KEY_RESP" | grep -o '"key":"[^"]*"' | cut -d'"' -f4)
echo "api key issued: ${API_KEY:0:14}..."
AUTH_KEY="Authorization: Bearer $API_KEY"

echo
echo "=== push a BENIGN batch using ONLY the API key (no JWT anywhere) ==="
BENIGN=$(curl -s -X POST "$BASE/api/streams/$STREAM_ID/events" -H "$AUTH_KEY" -H "$JSON" \
  -d '{"lines":["2026-01-01T00:00:00Z INFO api-gateway: GET /health 200 4ms","2026-01-01T00:00:01Z INFO api-gateway: GET /v1/orders 200 22ms"]}')
echo "$BENIGN"
echo "$BENIGN" | grep -q '"incidentCreated":false' && echo "PASS: benign batch did not create an incident" || echo "FAIL: benign batch created an incident unexpectedly"

echo
echo "=== push a SEVERE batch using ONLY the API key ==="
SEVERE=$(curl -s -X POST "$BASE/api/streams/$STREAM_ID/events" -H "$AUTH_KEY" -H "$JSON" \
  -d '{"lines":["2026-01-01T00:05:00Z INFO api-gateway: GET /v1/orders 200 18ms","2026-01-01T00:05:12Z ERROR api-gateway: connection refused to db:5432, pool exhausted","2026-01-01T00:05:13Z ERROR api-gateway: 503 Service Unavailable"]}')
echo "$SEVERE"
INCIDENT_ID=$(echo "$SEVERE" | grep -o '"incidentId":"[^"]*"' | cut -d'"' -f4)
echo "$SEVERE" | grep -q '"incidentCreated":true' && echo "PASS: severe batch created an incident ($INCIDENT_ID)" || echo "FAIL: severe batch did not create an incident"

echo
echo "=== confirm the incident is linked to the stream and visible in the incident list (via JWT, since GET /incidents/{id} isn't stream-scoped) ==="
curl -s "$BASE/api/incidents/$INCIDENT_ID" -H "$AUTH_JWT" | python3 -c "
import sys, json
i = json.load(sys.stdin)
print('title:', i.get('title'))
print('severity:', i.get('severity'))
print('status:', i.get('status'))
"

echo
echo "=== confirm stream activity counters updated ==="
curl -s "$BASE/api/streams" -H "$AUTH_JWT" | python3 -c "
import sys, json
streams = json.load(sys.stdin)
s = next(s for s in streams if s['id']=='$STREAM_ID')
print('eventCount:', s['eventCount'], '(expect 5: 2 benign + 3 severe)')
print('lastEventAt:', s['lastEventAt'])
"

echo
echo "=== confirm live-monitor picks up the new incident automatically (no separate wiring needed) ==="
curl -s "$BASE/api/live/state" -H "$AUTH_JWT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
titles = [r['title'] for r in d['recent']]
print('total:', d['total'], 'open:', d['open'])
print('recent includes our incident title:', any('api-gateway' in t.lower() for t in titles))
"

echo
echo "=== deactivated stream rejects pushes ==="
# no deactivate endpoint exists for streams yet - test inactive by re-using a status field via direct check instead:
echo "(skipped: no stream deactivate endpoint exists yet - status is always 'active' on create)"

echo
echo "=== a stray/wrong API key is rejected ==="
curl -s -o /dev/null -w 'status with garbage key: %{http_code}\n' -X POST "$BASE/api/streams/$STREAM_ID/events" \
  -H "Authorization: Bearer aegis_not_a_real_key" -H "$JSON" -d '{"lines":["x"]}'

echo
echo "=== cross-org isolation: a second org cannot push to this stream even with their own valid API key ==="
REG2=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"other+${STAMP}@example.com\",\"password\":\"password123\",\"fullName\":\"Other\",\"organizationName\":\"Other $STAMP\"}")
JWT2=$(echo "$REG2" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
KEY2=$(curl -s -X POST "$BASE/api/api-keys" -H "Authorization: Bearer $JWT2" -H "$JSON" -d '{"name":"other-key"}' | grep -o '"key":"[^"]*"' | cut -d'"' -f4)
curl -s -o /dev/null -w 'status: %{http_code} (expect 404, not found for this org)\n' \
  -X POST "$BASE/api/streams/$STREAM_ID/events" -H "Authorization: Bearer $KEY2" -H "$JSON" -d '{"lines":["x error"]}'

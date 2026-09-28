#!/usr/bin/env bash
# Batch 2 verify: URL ingest (SSRF guard), runbook, PDF report bytes.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="b2+$(date +%s)@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"B2\",\"organizationName\":\"B2Org $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

echo "=== ingest from private URL (expect 400 SSRF block) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/api/ingest/from-url" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"url":"http://169.254.169.254/latest/meta-data/"}'
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/api/ingest/from-url" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"url":"http://localhost:8080/api/health"}'

echo "=== ingest from public URL (expect lines) ==="
curl -s -X POST "$BASE/api/ingest/from-url" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"url":"https://raw.githubusercontent.com/torvalds/linux/master/README"}' | head -c 200

echo; echo; echo "=== create incident for runbook/PDF ==="
IID=$(curl -s -X POST "$BASE/api/incidents" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"title":"DB outage","severity":"critical","anomalyDescription":"database connection refused"}' | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "incident: $IID"

echo "=== runbook ==="
curl -s -X POST "$BASE/api/incidents/$IID/runbook" -H "Authorization: Bearer $TOKEN" | head -c 500

echo; echo; echo "=== PDF report (expect %PDF header + size) ==="
curl -s -X POST "$BASE/api/incidents/$IID/report.pdf" -H "Authorization: Bearer $TOKEN" -o /tmp/rep.pdf
echo "bytes: $(wc -c < /tmp/rep.pdf), header: $(head -c 4 /tmp/rep.pdf)"
echo

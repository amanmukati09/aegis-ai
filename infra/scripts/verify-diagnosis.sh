#!/usr/bin/env bash
# Phase 3 verification: the diagnosis pipeline (mask PII -> detect -> diagnose ->
# suggest -> persist incident). Works with or without a GROQ_API_KEY (falls back to
# deterministic dicts when no key is set). Run on the EC2:
#   bash infra/scripts/verify-diagnosis.sh
set -uo pipefail

BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="diag+$(date +%s)@example.com"

echo "=== register ==="
TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Dr SRE\",\"organizationName\":\"DiagOrg $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "token len: ${#TOKEN}"

echo
echo "=== ML models available (shows if GROQ key is set) ==="
curl -s http://localhost:8001/v1/models

echo
echo
echo "=== POST /api/diagnose (logs contain an IP that must be masked) ==="
RESULT=$(curl -s -X POST "$BASE/api/diagnose" -H "Authorization: Bearer $TOKEN" -H "$JSON" -d '{
  "logs": [
    "2026-09-28 14:02:11 ERROR api-gateway: upstream timeout after 30s from 10.0.0.5",
    "2026-09-28 14:02:12 WARN db-pool: 95% connections in use",
    "2026-09-28 14:02:13 ERROR payments-svc: connection refused to db:5432",
    "2026-09-28 14:02:15 ERROR api-gateway: 503 Service Unavailable rate 62%"
  ]
}')
echo "$RESULT"
IID=$(echo "$RESULT" | grep -o '"incidentId":"[^"]*"' | cut -d'"' -f4)

echo
echo "=== created incident persisted? GET /api/incidents/$IID ==="
curl -s "$BASE/api/incidents/$IID" -H "Authorization: Bearer $TOKEN"

echo
echo
echo "=== incident appears in list ==="
curl -s "$BASE/api/incidents" -H "Authorization: Bearer $TOKEN" | head -c 400
echo

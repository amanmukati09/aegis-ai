#!/usr/bin/env bash
# Phase 5 verification: streams, notifications, workspaces, api-keys, admin, alerting.
# Run on the EC2: bash infra/scripts/verify-platform.sh
set -uo pipefail

BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="plat+$(date +%s)@example.com"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Admin\",\"organizationName\":\"PlatOrg $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "token len: ${#TOKEN}"

echo; echo "=== create stream ==="
curl -s -X POST "$BASE/api/streams" -H "Authorization: Bearer $TOKEN" -H "$JSON" -d '{"name":"prod-metrics","description":"prod"}'
echo; echo "=== list streams ==="
curl -s "$BASE/api/streams" -H "Authorization: Bearer $TOKEN"

echo; echo; echo "=== create HIGH incident (fires alert to stdout + notification) ==="
curl -s -X POST "$BASE/api/incidents" -H "Authorization: Bearer $TOKEN" -H "$JSON" -d '{"title":"disk full","severity":"high"}' | head -c 200

echo; echo; echo "=== notifications (expect unreadCount>=1) ==="
curl -s "$BASE/api/notifications" -H "Authorization: Bearer $TOKEN"

echo; echo; echo "=== create api key (shown once) ==="
curl -s -X POST "$BASE/api/api-keys" -H "Authorization: Bearer $TOKEN" -H "$JSON" -d '{"name":"ci"}' | head -c 200

echo; echo; echo "=== create workspace ==="
curl -s -X POST "$BASE/api/workspaces" -H "Authorization: Bearer $TOKEN" -H "$JSON" -d '{"name":"Platform Team"}'

echo; echo; echo "=== admin: users + metrics ==="
curl -s "$BASE/api/admin/users" -H "Authorization: Bearer $TOKEN" | head -c 300
echo
curl -s "$BASE/api/admin/metrics" -H "Authorization: Bearer $TOKEN"
echo

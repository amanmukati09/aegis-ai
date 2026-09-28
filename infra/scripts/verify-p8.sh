#!/usr/bin/env bash
# Phase 8 verification: alert channels, invite member, RBAC gating, SQL runner + workflow
# (super_admin only). Run on the EC2: bash infra/scripts/verify-p8.sh
set -uo pipefail

BASE=http://localhost:8080
JSON='Content-Type: application/json'

# --- org_admin via self-registration ---
EMAIL="p8+$(date +%s)@example.com"
OA=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"OrgAdmin\",\"organizationName\":\"P8Org $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

echo "=== alert channels status (org_admin) ==="
curl -s "$BASE/api/admin/alerts/status" -H "Authorization: Bearer $OA"

echo; echo; echo "=== send test alert (fires to stdout) ==="
curl -s -X POST "$BASE/api/admin/alerts/test" -H "Authorization: Bearer $OA"

echo; echo; echo "=== invite member ==="
curl -s -X POST "$BASE/api/admin/users/invite" -H "Authorization: Bearer $OA" -H "$JSON" \
  -d '{"email":"invitee'"$(date +%s)"'@example.com","fullName":"Invited User","role":"member","tempPassword":"welcome123"}'

echo; echo; echo "=== org_admin BLOCKED from SQL runner (expect 403) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/api/admin/sql/execute" -H "Authorization: Bearer $OA" -H "$JSON" \
  -d '{"query":"SELECT 1"}'

echo "=== org_admin BLOCKED from workflow exec (expect 403) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/api/workflow/execute-command" -H "Authorization: Bearer $OA" -H "$JSON" \
  -d '{"command":"df -h"}'

# --- super_admin (bootstrapped) ---
echo; echo "=== login as bootstrapped super_admin ==="
SA=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" \
  -d '{"email":"admin@aegis.local","password":"'"${BOOTSTRAP_ADMIN_PASSWORD:-changeme-admin-pw}"'"}' \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -z "$SA" ]; then echo "(super_admin login failed — set BOOTSTRAP_ADMIN_PASSWORD and restart gateway)"; else
  echo "super_admin token len: ${#SA}"
  echo "=== SQL runner: list tables ==="
  curl -s "$BASE/api/admin/sql/tables" -H "Authorization: Bearer $SA" | head -c 300
  echo; echo "=== SQL runner: SELECT ==="
  curl -s -X POST "$BASE/api/admin/sql/execute" -H "Authorization: Bearer $SA" -H "$JSON" \
    -d '{"query":"SELECT count(*) AS orgs FROM organizations"}'
  echo; echo "=== SQL runner: blocked write (expect 400) ==="
  curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/api/admin/sql/execute" -H "Authorization: Bearer $SA" -H "$JSON" \
    -d '{"query":"DELETE FROM organizations"}'
  echo "=== workflow: allowlisted command ==="
  curl -s -X POST "$BASE/api/workflow/execute-command" -H "Authorization: Bearer $SA" -H "$JSON" -d '{"command":"df -h"}'
  echo; echo "=== workflow: blocked command (expect 400) ==="
  curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/api/workflow/execute-command" -H "Authorization: Bearer $SA" -H "$JSON" -d '{"command":"rm -rf /"}'
fi
echo

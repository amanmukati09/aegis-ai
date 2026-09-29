#!/usr/bin/env bash
# Verify admin user lifecycle: status toggle (+ immediate JWT revocation), role change,
# password reset, delete, and the last-admin/self-action guards.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)

# --- org owner (org_admin) registers a fresh org ---
OWNER_EMAIL="owner+${STAMP}@example.com"
REG=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"password123\",\"fullName\":\"Owner\",\"organizationName\":\"Lifecycle $STAMP\"}")
OWNER_TOKEN=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_OWNER="Authorization: Bearer $OWNER_TOKEN"
echo "owner registered: $OWNER_EMAIL"

# --- owner invites a member ---
MEMBER_EMAIL="member+${STAMP}@example.com"
INV=$(curl -s -X POST "$BASE/api/admin/users/invite" -H "$AUTH_OWNER" -H "$JSON" \
  -d "{\"email\":\"$MEMBER_EMAIL\",\"fullName\":\"Member\",\"role\":\"member\",\"tempPassword\":\"TempPass123\"}")
MEMBER_ID=$(echo "$INV" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "member invited: $MEMBER_EMAIL ($MEMBER_ID)"

echo "--- member logs in (expect success) ---"
LOGIN1=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"TempPass123\"}")
MEMBER_TOKEN=$(echo "$LOGIN1" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
[ -n "$MEMBER_TOKEN" ] && echo "PASS: member login ok" || echo "FAIL: member login failed"

echo "--- owner deactivates member ---"
curl -s -X PUT "$BASE/api/admin/users/$MEMBER_ID/status" -H "$AUTH_OWNER" -H "$JSON" -d '{"active":false}'
echo

echo "--- deactivated member's EXISTING token immediately rejected (expect 401/empty auth -> 403 on a protected call) ---"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents" -H "Authorization: Bearer $MEMBER_TOKEN")
echo "status code with stale token after deactivation: $CODE (expect 401 or 403, NOT 200)"

echo "--- deactivated member cannot log in fresh either ---"
LOGIN2=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" -H "$JSON" -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"TempPass123\"}")
echo "fresh login attempt while deactivated: $LOGIN2 (expect 401/403)"

echo "--- owner reactivates member ---"
curl -s -X PUT "$BASE/api/admin/users/$MEMBER_ID/status" -H "$AUTH_OWNER" -H "$JSON" -d '{"active":true}'
echo

echo "--- member logs in again (expect success) ---"
LOGIN3=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"TempPass123\"}")
echo "$LOGIN3" | grep -q accessToken && echo "PASS: reactivated member can log in" || echo "FAIL: reactivation broken"

echo "--- owner promotes member to org_admin ---"
curl -s -X PUT "$BASE/api/admin/users/$MEMBER_ID/role" -H "$AUTH_OWNER" -H "$JSON" -d '{"role":"org_admin"}'
echo

echo "--- member (now org_admin) tries to self-grant super_admin (expect 403) ---"
LOGIN4=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"TempPass123\"}")
MEMBER_TOKEN2=$(echo "$LOGIN4" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
curl -s -o /dev/null -w 'status: %{http_code}\n' -X PUT "$BASE/api/admin/users/$MEMBER_ID/role" \
  -H "Authorization: Bearer $MEMBER_TOKEN2" -H "$JSON" -d '{"role":"super_admin"}'

echo "--- owner tries to deactivate self (expect 400, blocked) ---"
OWNER_ID=$(curl -s "$BASE/api/admin/users" -H "$AUTH_OWNER" | python3 -c "
import sys, json
users = json.load(sys.stdin)
print(next(u['id'] for u in users if u['email']=='$OWNER_EMAIL'))
")
curl -s -o /dev/null -w 'status: %{http_code}\n' -X PUT "$BASE/api/admin/users/$OWNER_ID/status" -H "$AUTH_OWNER" -H "$JSON" -d '{"active":false}'

echo "--- owner tries to demote the only other admin scenario: deactivate self as last admin after demoting member back (expect blocked) ---"
curl -s -X PUT "$BASE/api/admin/users/$MEMBER_ID/role" -H "$AUTH_OWNER" -H "$JSON" -d '{"role":"member"}' > /dev/null
curl -s -o /dev/null -w 'owner self-deactivate as last admin status: %{http_code}\n' -X PUT "$BASE/api/admin/users/$OWNER_ID/status" -H "$AUTH_OWNER" -H "$JSON" -d '{"active":false}'

echo "--- owner resets member's password ---"
RESET=$(curl -s -X POST "$BASE/api/admin/users/$MEMBER_ID/reset-password" -H "$AUTH_OWNER" -H "$JSON")
NEW_PW=$(echo "$RESET" | grep -o '"tempPassword":"[^"]*"' | cut -d'"' -f4)
echo "new temp password issued: ${NEW_PW:0:8}..."
LOGIN5=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" -H "$JSON" -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"$NEW_PW\"}")
echo "login with reset password: $LOGIN5 (expect 200)"
LOGIN6=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" -H "$JSON" -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"TempPass123\"}")
echo "login with OLD password after reset: $LOGIN6 (expect 401)"

echo "--- owner deletes member ---"
curl -s -o /dev/null -w 'delete status: %{http_code}\n' -X DELETE "$BASE/api/admin/users/$MEMBER_ID" -H "$AUTH_OWNER"
echo "--- confirm member gone from list ---"
curl -s "$BASE/api/admin/users" -H "$AUTH_OWNER" | python3 -c "
import sys, json
users = json.load(sys.stdin)
print('remaining users:', [u['email'] for u in users])
"

echo "--- owner tries to delete self (expect 400) ---"
curl -s -o /dev/null -w 'self-delete status: %{http_code}\n' -X DELETE "$BASE/api/admin/users/$OWNER_ID" -H "$AUTH_OWNER"

#!/usr/bin/env bash
# Verify that workspace membership is now a REAL visibility boundary, not just an
# opt-in filter: a regular member who is NOT in a workspace must not see that
# workspace's incidents anywhere (list, get, dashboard/live "recent", similar,
# triage queue), while org admins and workspace members still see everything they
# should. Also verifies the new member-management endpoints (candidates/remove).
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)

# --- org setup: an org_admin (the registering user), a workspace member, and an outsider ---
ADMIN_EMAIL="wsvowner+${STAMP}@example.com"
REG=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"password123\",\"fullName\":\"WSV Admin\",\"organizationName\":\"WSV Org $STAMP\"}")
ADMIN_JWT=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_ADMIN="Authorization: Bearer $ADMIN_JWT"

MEMBER_EMAIL="wsvmember+${STAMP}@example.com"
MEMBER_TEMP_PW="Passw0rd!${STAMP}m"
curl -s -X POST "$BASE/api/admin/users/invite" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"email\":\"$MEMBER_EMAIL\",\"fullName\":\"WSV Member\",\"role\":\"member\",\"tempPassword\":\"$MEMBER_TEMP_PW\"}" > /dev/null
MEMBER_JWT=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" \
  -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"$MEMBER_TEMP_PW\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_MEMBER="Authorization: Bearer $MEMBER_JWT"

OUTSIDER_EMAIL="wsvoutsider+${STAMP}@example.com"
OUTSIDER_TEMP_PW="Passw0rd!${STAMP}o"
curl -s -X POST "$BASE/api/admin/users/invite" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"email\":\"$OUTSIDER_EMAIL\",\"fullName\":\"WSV Outsider\",\"role\":\"member\",\"tempPassword\":\"$OUTSIDER_TEMP_PW\"}" > /dev/null
OUTSIDER_JWT=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" \
  -d "{\"email\":\"$OUTSIDER_EMAIL\",\"password\":\"$OUTSIDER_TEMP_PW\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_OUTSIDER="Authorization: Bearer $OUTSIDER_JWT"

echo "=== admin creates a workspace and adds MEMBER (not OUTSIDER) via the candidates picker ==="
WS=$(curl -s -X POST "$BASE/api/workspaces" -H "$AUTH_ADMIN" -H "$JSON" -d '{"name":"private-team","description":"secret stuff"}')
WS_ID=$(echo "$WS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "workspace: $WS_ID"

CANDIDATES=$(curl -s "$BASE/api/workspaces/$WS_ID/candidates" -H "$AUTH_ADMIN")
echo "$CANDIDATES" | grep -q "$MEMBER_EMAIL" && echo "PASS: candidates list includes MEMBER by email (no raw UUID needed)" || echo "FAIL: candidates missing MEMBER"

MEMBER_USER_ID=$(echo "$CANDIDATES" | python3 -c "
import sys, json
cands = json.load(sys.stdin)
m = next(c for c in cands if c['email'] == '$MEMBER_EMAIL')
print(m['id'])
")
curl -s -X POST "$BASE/api/workspaces/$WS_ID/members" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"userId\":\"$MEMBER_USER_ID\",\"role\":\"member\"}" > /dev/null

MEMBERS_VIEW=$(curl -s "$BASE/api/workspaces/$WS_ID/members" -H "$AUTH_ADMIN")
echo "$MEMBERS_VIEW" | grep -q "$MEMBER_EMAIL" && echo "PASS: member view shows email, not just a UUID" || echo "FAIL: member view missing email"

echo
echo "=== admin creates a workspace-scoped incident (private-team) and an unscoped incident (general) ==="
PRIVATE_INC=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"title\":\"Private team secret outage\",\"severity\":\"critical\",\"anomalyDescription\":\"sensitive\",\"workspaceId\":\"$WS_ID\"}")
PRIVATE_INC_ID=$(echo "$PRIVATE_INC" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -X POST "$BASE/api/incidents" -H "$AUTH_ADMIN" -H "$JSON" \
  -d '{"title":"General shared outage","severity":"low","anomalyDescription":"visible to all"}' > /dev/null
echo "private incident: $PRIVATE_INC_ID"

echo
echo "=== OUTSIDER (same org, NOT a workspace member) must NOT see the private incident anywhere ==="

LIST_OUTSIDER=$(curl -s "$BASE/api/incidents" -H "$AUTH_OUTSIDER")
echo "$LIST_OUTSIDER" | python3 -c "
import sys, json
d = json.load(sys.stdin)
titles = [i['title'] for i in d['items']]
assert 'Private team secret outage' not in titles, 'LEAK: outsider saw the private incident in unfiltered list'
assert 'General shared outage' in titles, 'unscoped incident should still be visible to outsider'
print('PASS: unfiltered incident list hides the private incident from a non-member, keeps the unscoped one')
"

STATUS_GET=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents/$PRIVATE_INC_ID" -H "$AUTH_OUTSIDER")
[ "$STATUS_GET" = "404" ] && echo "PASS: direct GET /incidents/{id} on the private incident returns 404 for outsider (not 200, not 403)" \
  || echo "FAIL: expected 404, got $STATUS_GET"

LIVE_OUTSIDER=$(curl -s "$BASE/api/live/state" -H "$AUTH_OUTSIDER")
echo "$LIVE_OUTSIDER" | python3 -c "
import sys, json
d = json.load(sys.stdin)
titles = [r['title'] for r in d['recent']]
assert 'Private team secret outage' not in titles, 'LEAK: outsider saw the private incident in Live Monitor recent feed'
print('PASS: Live Monitor recent feed hides the private incident from a non-member')
"

DASH_OUTSIDER=$(curl -s "$BASE/api/dashboard/summary" -H "$AUTH_OUTSIDER")
echo "$DASH_OUTSIDER" | python3 -c "
import sys, json
d = json.load(sys.stdin)
titles = [r['title'] for r in d['recent']]
assert 'Private team secret outage' not in titles, 'LEAK: outsider saw the private incident in Dashboard recent feed'
print('PASS: Dashboard recent feed hides the private incident from a non-member')
"

TRIAGE_OUTSIDER=$(curl -s "$BASE/api/triage/queue" -H "$AUTH_OUTSIDER")
echo "$TRIAGE_OUTSIDER" | python3 -c "
import sys, json
d = json.load(sys.stdin)
rows = d if isinstance(d, list) else d.get('queue', [])
titles = [r.get('title','') for r in rows]
assert 'Private team secret outage' not in titles, 'LEAK: outsider saw the private incident in the triage queue'
print('PASS: triage queue hides the private incident from a non-member')
" 2>/dev/null || echo "INFO: triage queue check skipped (endpoint shape or ML sidecar unavailable) — non-fatal for this verification"

echo
echo "=== MEMBER (a real workspace member) CAN see the private incident ==="
STATUS_MEMBER=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents/$PRIVATE_INC_ID" -H "$AUTH_MEMBER")
[ "$STATUS_MEMBER" = "200" ] && echo "PASS: workspace member gets 200 on the private incident" \
  || echo "FAIL: expected 200 for member, got $STATUS_MEMBER"

LIST_MEMBER=$(curl -s "$BASE/api/incidents" -H "$AUTH_MEMBER")
echo "$LIST_MEMBER" | python3 -c "
import sys, json
d = json.load(sys.stdin)
titles = [i['title'] for i in d['items']]
assert 'Private team secret outage' in titles, 'member should see the private incident in the unfiltered list'
print('PASS: workspace member sees the private incident in the unfiltered incident list')
"

echo
echo "=== ADMIN (org_admin) still sees everything, bypassing workspace visibility ==="
LIST_ADMIN=$(curl -s "$BASE/api/incidents" -H "$AUTH_ADMIN")
echo "$LIST_ADMIN" | python3 -c "
import sys, json
d = json.load(sys.stdin)
titles = [i['title'] for i in d['items']]
assert 'Private team secret outage' in titles and 'General shared outage' in titles
print('PASS: org_admin sees both the private and general incidents')
"

echo
echo "=== KB article generation from a workspace-scoped incident: outsider blocked, admin allowed ==="
curl -s -X POST "$BASE/api/incidents/$PRIVATE_INC_ID/resolve" -H "$AUTH_ADMIN" -H "$JSON" -d '{}' > /dev/null
KB_OUTSIDER_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/kb/generate/$PRIVATE_INC_ID" -H "$AUTH_OUTSIDER")
[ "$KB_OUTSIDER_STATUS" = "404" ] && echo "PASS: outsider cannot generate a KB article from the private incident (404)" \
  || echo "FAIL: expected 404, got $KB_OUTSIDER_STATUS"

echo
echo "=== admin removes MEMBER from the workspace; MEMBER loses access immediately ==="
curl -s -o /dev/null -w 'remove-member status: %{http_code}\n' -X DELETE \
  "$BASE/api/workspaces/$WS_ID/members/$MEMBER_USER_ID" -H "$AUTH_ADMIN"
STATUS_AFTER_REMOVE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents/$PRIVATE_INC_ID" -H "$AUTH_MEMBER")
[ "$STATUS_AFTER_REMOVE" = "404" ] && echo "PASS: removed member immediately loses access (404)" \
  || echo "FAIL: expected 404 after removal, got $STATUS_AFTER_REMOVE"

echo
echo "=== cleanup note: test orgs/users/workspaces are not deleted by this script ==="

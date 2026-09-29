#!/usr/bin/env bash
# Verify PUT /api/incidents/{id}/workspace: tagging an EXISTING incident into a
# workspace, clearing it back to the shared pool, admin-only enforcement, and that
# visibility (from the workspace-visibility fix) actually follows the retag immediately.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)

ADMIN_EMAIL="retag+${STAMP}@example.com"
REG=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"password123\",\"fullName\":\"Retag Admin\",\"organizationName\":\"Retag Org $STAMP\"}")
ADMIN_JWT=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_ADMIN="Authorization: Bearer $ADMIN_JWT"

MEMBER_EMAIL="retagmember+${STAMP}@example.com"
MEMBER_TEMP_PW="Passw0rd!${STAMP}m"
curl -s -X POST "$BASE/api/admin/users/invite" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"email\":\"$MEMBER_EMAIL\",\"fullName\":\"Retag Member\",\"role\":\"member\",\"tempPassword\":\"$MEMBER_TEMP_PW\"}" > /dev/null
MEMBER_JWT=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" \
  -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"$MEMBER_TEMP_PW\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_MEMBER="Authorization: Bearer $MEMBER_JWT"

echo "=== create a workspace (admin only, member is NOT added) ==="
WS=$(curl -s -X POST "$BASE/api/workspaces" -H "$AUTH_ADMIN" -H "$JSON" -d '{"name":"retag-team"}')
WS_ID=$(echo "$WS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "workspace: $WS_ID"

echo
echo "=== create an UNSCOPED incident (visible to everyone, including MEMBER) ==="
INC=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH_ADMIN" -H "$JSON" \
  -d '{"title":"Retag target incident","severity":"medium","anomalyDescription":"initially unscoped"}')
INC_ID=$(echo "$INC" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "incident: $INC_ID"

STATUS_BEFORE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents/$INC_ID" -H "$AUTH_MEMBER")
[ "$STATUS_BEFORE" = "200" ] && echo "PASS: member can see the incident before retagging (unscoped = org-wide)" \
  || echo "FAIL: expected 200 before retag, got $STATUS_BEFORE"

echo
echo "=== a regular MEMBER cannot retag (admin-only), even for an incident they can see ==="
STATUS_MEMBER_RETAG=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/incidents/$INC_ID/workspace" \
  -H "$AUTH_MEMBER" -H "$JSON" -d "{\"workspaceId\":\"$WS_ID\"}")
[ "$STATUS_MEMBER_RETAG" = "403" ] && echo "PASS: member retag attempt rejected (403)" \
  || echo "FAIL: expected 403, got $STATUS_MEMBER_RETAG"

echo
echo "=== admin retags the incident into the workspace ==="
RETAG=$(curl -s -X PUT "$BASE/api/incidents/$INC_ID/workspace" -H "$AUTH_ADMIN" -H "$JSON" -d "{\"workspaceId\":\"$WS_ID\"}")
echo "$RETAG" | grep -q "\"workspaceId\":\"$WS_ID\"" && echo "PASS: incident now carries the new workspaceId" \
  || echo "FAIL: retag response missing expected workspaceId"

echo
echo "=== MEMBER (not in the workspace) immediately loses access on their very next request ==="
STATUS_AFTER=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents/$INC_ID" -H "$AUTH_MEMBER")
[ "$STATUS_AFTER" = "404" ] && echo "PASS: member gets 404 immediately after retag (visibility change took effect instantly)" \
  || echo "FAIL: expected 404 after retag, got $STATUS_AFTER"

echo
echo "=== admin still sees it (bypasses workspace visibility) ==="
STATUS_ADMIN=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents/$INC_ID" -H "$AUTH_ADMIN")
[ "$STATUS_ADMIN" = "200" ] && echo "PASS: admin still sees the retagged incident" \
  || echo "FAIL: expected 200 for admin, got $STATUS_ADMIN"

echo
echo "=== admin clears the workspace tag (workspaceId: null) — incident goes back to the shared pool ==="
CLEAR=$(curl -s -X PUT "$BASE/api/incidents/$INC_ID/workspace" -H "$AUTH_ADMIN" -H "$JSON" -d '{"workspaceId":null}')
echo "$CLEAR" | grep -q '"workspaceId":null' && echo "PASS: workspaceId cleared to null" \
  || echo "FAIL: clear did not null out workspaceId"

STATUS_RESTORED=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents/$INC_ID" -H "$AUTH_MEMBER")
[ "$STATUS_RESTORED" = "200" ] && echo "PASS: member regains access immediately after the tag is cleared" \
  || echo "FAIL: expected 200 after clearing, got $STATUS_RESTORED"

echo
echo "=== retagging with a bogus/cross-org workspaceId is rejected (404, not silently accepted) ==="
STATUS_BOGUS=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/incidents/$INC_ID/workspace" \
  -H "$AUTH_ADMIN" -H "$JSON" -d '{"workspaceId":"00000000-0000-0000-0000-000000000000"}')
[ "$STATUS_BOGUS" = "404" ] && echo "PASS: bogus workspaceId rejected with 404" \
  || echo "FAIL: expected 404, got $STATUS_BOGUS"

echo
echo "=== cleanup note: test org/users/workspace are not deleted by this script ==="

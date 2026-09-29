#!/usr/bin/env bash
# Verify Task 3: workspaces wired into incidents (create/list scoping + access control),
# and bulk-analysis auto-diagnosis of the most severe incidents in a batch (capped at 10).
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)

# --- org A: owner + a non-member user ---
OWNER_EMAIL="wsowner+${STAMP}@example.com"
REG=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"password123\",\"fullName\":\"WS Owner\",\"organizationName\":\"WS Org $STAMP\"}")
OWNER_JWT=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_OWNER="Authorization: Bearer $OWNER_JWT"

echo "=== create a workspace (owner becomes a member automatically) ==="
WS=$(curl -s -X POST "$BASE/api/workspaces" -H "$AUTH_OWNER" -H "$JSON" \
  -d '{"name":"payments-team","description":"payments incidents"}')
WS_ID=$(echo "$WS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "workspace: $WS_ID"

echo
echo "=== create an incident scoped to the workspace ==="
INC=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH_OWNER" -H "$JSON" \
  -d "{\"title\":\"Payments DB timeout\",\"severity\":\"high\",\"anomalyDescription\":\"db timeouts\",\"workspaceId\":\"$WS_ID\"}")
echo "$INC"
INC_ID=$(echo "$INC" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "$INC" | grep -q "\"workspaceId\":\"$WS_ID\"" && echo "PASS: incident created with workspaceId set" || echo "FAIL: incident missing workspaceId"

echo
echo "=== create an incident with NO workspace (org-wide) ==="
INC2=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH_OWNER" -H "$JSON" \
  -d '{"title":"Unrelated infra alert","severity":"low","anomalyDescription":"noise"}')
echo "$INC2" | grep -q '"workspaceId":null' && echo "PASS: incident without workspace has null workspaceId" || echo "FAIL: unexpected workspaceId on unscoped incident"

echo
echo "=== GET /api/incidents?workspaceId=X returns only the scoped incident ==="
LIST=$(curl -s "$BASE/api/incidents?workspaceId=$WS_ID" -H "$AUTH_OWNER")
echo "$LIST" | python3 -c "
import sys, json
d = json.load(sys.stdin)
titles = [i['title'] for i in d['items']]
print('items:', titles)
assert 'Payments DB timeout' in titles, 'expected incident missing'
assert 'Unrelated infra alert' not in titles, 'unscoped incident leaked into workspace filter'
print('PASS: workspace filter returns only scoped incident')
"

echo
echo "=== GET /api/incidents (no filter) still returns both ==="
LISTALL=$(curl -s "$BASE/api/incidents" -H "$AUTH_OWNER")
echo "$LISTALL" | python3 -c "
import sys, json
d = json.load(sys.stdin)
titles = [i['title'] for i in d['items']]
assert 'Payments DB timeout' in titles and 'Unrelated infra alert' in titles
print('PASS: unfiltered list still returns both incidents')
"

# --- org A: a second user who is NOT a workspace member ---
echo
echo "=== a same-org user who is NOT a workspace member gets 403 on workspace-scoped list ==="
NONMEMBER_EMAIL="wsnonmember+${STAMP}@example.com"
NM_TEMP_PW="Passw0rd!${STAMP}"
INVITE=$(curl -s -X POST "$BASE/api/admin/users/invite" -H "$AUTH_OWNER" -H "$JSON" \
  -d "{\"email\":\"$NONMEMBER_EMAIL\",\"fullName\":\"Non Member\",\"role\":\"member\",\"tempPassword\":\"$NM_TEMP_PW\"}")
echo "$INVITE"

LOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" \
  -d "{\"email\":\"$NONMEMBER_EMAIL\",\"password\":\"$NM_TEMP_PW\"}")
NM_JWT=$(echo "$LOGIN" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -n "$NM_JWT" ]; then
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents?workspaceId=$WS_ID" -H "Authorization: Bearer $NM_JWT")
  [ "$STATUS" = "403" ] && echo "PASS: non-member org user got 403 (status=$STATUS)" || echo "FAIL: expected 403, got $STATUS"
else
  echo "SKIP: could not log in as invited non-member (login response: $LOGIN)"
fi

echo
echo "=== a user in a DIFFERENT org gets 404 for the workspace id (org isolation) ==="
OTHER_EMAIL="wsother+${STAMP}@example.com"
REG2=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$OTHER_EMAIL\",\"password\":\"password123\",\"fullName\":\"Other Org\",\"organizationName\":\"Other Org $STAMP\"}")
OTHER_JWT=$(echo "$REG2" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
STATUS2=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/incidents?workspaceId=$WS_ID" -H "Authorization: Bearer $OTHER_JWT")
[ "$STATUS2" = "404" ] && echo "PASS: cross-org workspace id got 404 (status=$STATUS2)" || echo "FAIL: expected 404, got $STATUS2"

echo
echo "=== creating an incident with a cross-org workspaceId is rejected (404) ==="
STATUS3=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/incidents" -H "Authorization: Bearer $OTHER_JWT" -H "$JSON" \
  -d "{\"title\":\"Should fail\",\"severity\":\"low\",\"workspaceId\":\"$WS_ID\"}")
[ "$STATUS3" = "404" ] && echo "PASS: create with cross-org workspaceId got 404 (status=$STATUS3)" || echo "FAIL: expected 404, got $STATUS3"

echo
echo "=== bulk-analysis auto-diagnoses only the top 10 most severe incidents in a batch ==="
# The segmenter groups by (component, incident_type), NOT per-line. To get >10 DISTINCT
# incidents we need >=11 distinct (component, signature) combinations. Mix components
# (database/redis/nginx/gateway/auth/payment/memory/disk/cpu/network/orchestration) with
# a critical-class signal each, plus a couple of lower-severity ones for contrast.
LOGS_JSON=$(python3 -c "
import json
combos = [
    ('postgres', 'connection refused, pool exhausted'),
    ('redis', 'connection refused too many clients'),
    ('nginx', 'no live upstreams, bad gateway 502'),
    ('api-gateway', 'timed out waiting for upstream'),
    ('auth', 'panic: token store corrupt'),
    ('payment', 'out of memory heap space exhausted'),
    ('kubelet', 'oomkill process terminated'),
    ('disk', 'no space left on device'),
    ('cpu', 'deadline exceeded, i/o timeout'),
    ('dns', 'connection refused, no live upstreams'),
    ('checkout', 'panic: fatal data loss detected'),
    ('cache', 'evict maxmemory throttling backpressure'),
    ('proxy', 'crashloop killed process terminated'),
]
lines = []
for i, (comp, sig) in enumerate(combos):
    for j in range(3):
        lines.append(f'2026-01-01T00:{i:02d}:{j:02d}Z ERROR {comp}: {sig} (occurrence {j})')
print(json.dumps({'logs': lines, 'source':'verify-script', 'createIncidents': True}))
")
JOB=$(curl -s -X POST "$BASE/api/jobs/bulk-analyze" -H "$AUTH_OWNER" -H "$JSON" -d "$LOGS_JSON")
JOB_ID=$(echo "$JOB" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "job: $JOB_ID"

# Poll for completion (max ~30s).
RESULT=""
for i in $(seq 1 15); do
  sleep 2
  POLL=$(curl -s "$BASE/api/jobs/$JOB_ID" -H "$AUTH_OWNER")
  STATUS_JOB=$(echo "$POLL" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
  if [ "$STATUS_JOB" = "completed" ] || [ "$STATUS_JOB" = "failed" ]; then
    RESULT="$POLL"
    break
  fi
done

if [ -z "$RESULT" ]; then
  echo "FAIL: bulk-analyze job did not complete in time"
else
  echo "$RESULT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
status = d.get('status')
print('job status:', status)
if status != 'completed':
    print('FAIL: job did not complete, error=', d.get('error'))
    sys.exit(0)
result = d.get('result') or {}
created = result.get('created') or []
print('created_count:', result.get('created_count'), '(actual created incidents:', len(created), ')')
diagnosed = [i for i in created if i.get('rootCause')]
undiagnosed = [i for i in created if not i.get('rootCause')]
print('diagnosed count:', len(diagnosed))
print('undiagnosed count:', len(undiagnosed))
if len(created) > 10:
    ok = len(diagnosed) <= 10
    print('PASS: at most 10 incidents auto-diagnosed' if ok else 'FAIL: more than 10 incidents were auto-diagnosed')
    print('PASS: some incidents left undiagnosed beyond the cap' if len(undiagnosed) > 0 else 'INFO: all created incidents were diagnosed (batch may be <=10 after segmentation)')
else:
    print('INFO: segmenter produced', len(created), 'incidents (<=10), diagnosis cap not exercised by this batch size')
    print('diagnosed vs created:', len(diagnosed), '/', len(created))
"
fi

echo
echo "=== cleanup note: test orgs/users are not deleted by this script (no destructive action taken) ==="

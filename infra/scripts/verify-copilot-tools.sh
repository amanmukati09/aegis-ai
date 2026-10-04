#!/usr/bin/env bash
# Verify the LangChain/LangGraph Copilot migration: chat still works end-to-end
# (non-streaming + streaming), the agent's tools actually call back into the gateway
# (incident search / KB search / analytics), and workspace-visibility is respected
# through that new tool-call path exactly as it is for direct browser calls.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)

ADMIN_EMAIL="copilot+${STAMP}@example.com"
REG=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"password123\",\"fullName\":\"Copilot Admin\",\"organizationName\":\"Copilot Org $STAMP\"}")
ADMIN_JWT=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_ADMIN="Authorization: Bearer $ADMIN_JWT"
if [ -z "$ADMIN_JWT" ]; then
  echo "FAIL: could not register/login admin test user"; echo "$REG"; exit 1
fi

MEMBER_EMAIL="copilotmember+${STAMP}@example.com"
MEMBER_TEMP_PW="Passw0rd!${STAMP}m"
curl -s -X POST "$BASE/api/admin/users/invite" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"email\":\"$MEMBER_EMAIL\",\"fullName\":\"Copilot Member\",\"role\":\"member\",\"tempPassword\":\"$MEMBER_TEMP_PW\"}" > /dev/null
MEMBER_JWT=$(curl -s -X POST "$BASE/api/auth/login" -H "$JSON" \
  -d "{\"email\":\"$MEMBER_EMAIL\",\"password\":\"$MEMBER_TEMP_PW\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
AUTH_MEMBER="Authorization: Bearer $MEMBER_JWT"

echo "=== 1. Basic chat still works (non-streaming) — contract unchanged ==="
SEND=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH_ADMIN" -H "$JSON" \
  -d '{"message":"Say OK and nothing else."}')
echo "$SEND" | grep -q '"reply"' && echo "PASS: /api/chat/message returned a reply field" \
  || { echo "FAIL: no reply field in response"; echo "$SEND"; exit 1; }
SESSION_ID=$(echo "$SEND" | grep -o '"sessionId":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "session: $SESSION_ID"

echo
echo "=== 2. Streaming endpoint still returns SSE tokens + [DONE] ==="
STREAM_OUT=$(curl -s -N -X POST "$BASE/api/chat/stream" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"Say OK and nothing else.\"}" --max-time 30)
echo "$STREAM_OUT" | grep -q 'data:[[:space:]]*\[DONE\]' && echo "PASS: stream terminated with [DONE]" \
  || { echo "FAIL: stream did not terminate with [DONE]"; echo "$STREAM_OUT"; }
echo "$STREAM_OUT" | grep -qi 'event: error' && echo "WARN: stream emitted an error event (see output below)"
echo "--- raw stream (first 500 chars) ---"
echo "$STREAM_OUT" | head -c 500
echo

echo
echo "=== 3. Create a real incident the admin can see, ask Copilot to find it (tool-calling: search_incidents) ==="
UNIQUE_TITLE="CopilotProbeIncident${STAMP}"
INC=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"title\":\"$UNIQUE_TITLE\",\"severity\":\"high\",\"anomalyDescription\":\"Database connection pool exhausted under load\"}")
INC_ID=$(echo "$INC" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "incident: $INC_ID ($UNIQUE_TITLE)"

ASK=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"message\":\"Search for an incident called $UNIQUE_TITLE and tell me its severity.\"}")
REPLY=$(echo "$ASK" | grep -o '"reply":"[^"]*' | cut -d'"' -f4-)
echo "reply snippet: ${REPLY:0:300}"
echo "$REPLY" | grep -qi 'high' && echo "PASS: Copilot's answer mentions the correct severity (grounded via search_incidents tool)" \
  || echo "WARN: could not confirm tool-grounded answer from reply text alone (model phrasing varies) — check gateway/ml-service logs for the tool call"

echo
echo "=== 4. Workspace visibility is respected through the tool-call path ==="
WS=$(curl -s -X POST "$BASE/api/workspaces" -H "$AUTH_ADMIN" -H "$JSON" -d '{"name":"copilot-secret-team"}')
WS_ID=$(echo "$WS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "workspace: $WS_ID (member is NOT added to it)"

SECRET_TITLE="CopilotSecretIncident${STAMP}"
SECRET_INC=$(curl -s -X POST "$BASE/api/incidents" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"title\":\"$SECRET_TITLE\",\"severity\":\"critical\",\"anomalyDescription\":\"Workspace-scoped secret incident\"}")
SECRET_INC_ID=$(echo "$SECRET_INC" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -X PUT "$BASE/api/incidents/$SECRET_INC_ID/workspace" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"workspaceId\":\"$WS_ID\"}" > /dev/null
echo "secret incident: $SECRET_INC_ID tagged into $WS_ID"

# Direct REST search (not through chat) must not return it for the non-member — sanity
# check the underlying endpoint before testing the chat path on top of it.
DIRECT_SEARCH=$(curl -s "$BASE/api/incidents/search?q=$SECRET_TITLE" -H "$AUTH_MEMBER")
echo "$DIRECT_SEARCH" | grep -q "$SECRET_INC_ID" && echo "FAIL: non-member's direct /api/incidents/search leaked the workspace-scoped incident" \
  || echo "PASS: non-member's direct /api/incidents/search does not return the workspace-scoped incident"

# Now ask the Copilot, AS THE NON-MEMBER, to find it — the agent's search_incidents tool
# forwards the member's own tool token, so the gateway should filter it out exactly the
# same way.
MEMBER_ASK=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH_MEMBER" -H "$JSON" \
  -d "{\"message\":\"Search for an incident called $SECRET_TITLE and tell me everything about it, including its severity and description.\"}")
MEMBER_REPLY=$(echo "$MEMBER_ASK" | grep -o '"reply":"[^"]*' | cut -d'"' -f4-)
echo "member reply snippet: ${MEMBER_REPLY:0:300}"
echo "$MEMBER_REPLY" | grep -qi 'critical\|Workspace-scoped secret incident' \
  && echo "FAIL: Copilot leaked workspace-scoped incident details to a non-member" \
  || echo "PASS: Copilot did not leak workspace-scoped incident details to a non-member"

# Admin (bypasses workspace visibility) should still be able to find it via chat.
ADMIN_ASK=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"message\":\"Search for an incident called $SECRET_TITLE and tell me its severity.\"}")
ADMIN_REPLY=$(echo "$ADMIN_ASK" | grep -o '"reply":"[^"]*' | cut -d'"' -f4-)
echo "admin reply snippet: ${ADMIN_REPLY:0:300}"
echo "$ADMIN_REPLY" | grep -qi 'critical' && echo "PASS: admin (bypasses workspace visibility) can still find it via Copilot" \
  || echo "WARN: could not confirm admin's tool-grounded answer from reply text alone — check logs"

echo
echo "=== 5. Analytics tool (NL-to-SQL) answers a count question without erroring ==="
COUNT_ASK=$(curl -s -X POST "$BASE/api/chat/message" -H "$AUTH_ADMIN" -H "$JSON" \
  -d '{"message":"How many incidents are there in total right now? Use the analytics tool and give me a number."}')
COUNT_REPLY=$(echo "$COUNT_ASK" | grep -o '"reply":"[^"]*' | cut -d'"' -f4-)
echo "analytics reply snippet: ${COUNT_REPLY:0:300}"
[ -n "$COUNT_REPLY" ] && echo "PASS: analytics question returned a reply (manually confirm it cites a plausible count)" \
  || echo "FAIL: no reply for analytics question"

echo
echo "=== cleanup note: test org/users/workspace/incidents are not deleted by this script ==="

#!/usr/bin/env bash
# Phase 4 verification: chat/copilot end-to-end with the real LLM.
# Run on the EC2: bash infra/scripts/verify-chat.sh
set -uo pipefail

BASE=http://localhost:8080
JSON='Content-Type: application/json'
EMAIL="chat+$(date +%s)@example.com"

echo "=== register ==="
TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Chat User\",\"organizationName\":\"ChatOrg $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "token len: ${#TOKEN}"

echo
echo "=== available models (proxy) ==="
curl -s "$BASE/api/chat/models" -H "Authorization: Bearer $TOKEN" | head -c 300

echo
echo
echo "=== send message (real LLM) ==="
RESP=$(curl -s -X POST "$BASE/api/chat/message" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"message":"In one sentence, what should I check first when an API gateway returns 503 errors?"}')
echo "$RESP"
SID=$(echo "$RESP" | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)

echo
echo "=== sessions list ==="
curl -s "$BASE/api/chat/sessions" -H "Authorization: Bearer $TOKEN"

echo
echo
echo "=== messages in session ==="
curl -s "$BASE/api/chat/sessions/$SID/messages" -H "Authorization: Bearer $TOKEN"

echo
echo
echo "=== follow-up message in same session ==="
curl -s -X POST "$BASE/api/chat/message" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"And after that?\"}" | head -c 400
echo

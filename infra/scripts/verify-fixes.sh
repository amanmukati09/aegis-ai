#!/usr/bin/env bash
# Confirm the two hardening fixes: RL Q-table persisted to disk (volume perms) and the
# refactored chat stream still emits clean session + token + [DONE] frames.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'

echo "--- ml_data pickle on disk (perms fix: owned by aegis, written) ---"
docker exec aegisai-ml ls -la /app/data || echo "(no data dir)"

echo
echo "--- chat stream frames (refactored controller) ---"
TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"fix+$(date +%s)@x.com\",\"password\":\"password123\",\"fullName\":\"Fix\",\"organizationName\":\"Fix $(date +%s)\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

curl -s -N -X POST "$BASE/api/chat/stream" -H "Authorization: Bearer $TOKEN" -H "$JSON" \
  -d '{"message":"say hi in exactly two words"}' | tr -d '\r' | head -c 500
echo
echo "--- (expect: event:session, then data: tokens, then data:[DONE]; NO __AEGIS_STREAM_ERROR__) ---"

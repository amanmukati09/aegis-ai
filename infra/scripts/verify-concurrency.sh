#!/usr/bin/env bash
# Verify Task 4: concurrency config is actually wired end-to-end and the gateway
# survives a burst of concurrent requests without pool exhaustion or dropped connections.
# This is a lightweight smoke check, not a full load test (no load-testing tool assumed
# to be installed on the host) — it proves the config took effect and nothing regresses
# under a realistic multi-user burst, not that the box literally handles 5000 users.
set -uo pipefail
BASE=http://localhost:8080
JSON='Content-Type: application/json'
STAMP=$(date +%s)
EMAIL="conc+${STAMP}@example.com"

echo "=== confirm concurrency env vars reached the gateway container ==="
docker exec aegisai-gateway env | grep -E 'DB_POOL_MAX_SIZE|TOMCAT_THREADS_MAX' | sort

echo
echo "=== confirm Postgres accepted the higher max_connections/shared_buffers ==="
docker exec aegisai-postgres psql -U "${POSTGRES_USER:-aegisai}" -d "${POSTGRES_DB:-aegisai}" -tAc "show max_connections;" | xargs -I{} echo "max_connections={}"
docker exec aegisai-postgres psql -U "${POSTGRES_USER:-aegisai}" -d "${POSTGRES_DB:-aegisai}" -tAc "show shared_buffers;" | xargs -I{} echo "shared_buffers={}"

echo
echo "=== register 20 distinct users (simulating 20 distinct concurrent principals — the"
echo "    per-principal rate limiter means a real burst of many DIFFERENT users, not one"
echo "    token hammering the API, is the realistic concurrency shape) ==="
TOKENS_FILE=$(mktemp)
for u in $(seq 1 20); do
  R=$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
    -d "{\"email\":\"conc${u}+${STAMP}@example.com\",\"password\":\"password123\",\"fullName\":\"Conc $u\",\"organizationName\":\"Conc Org $u $STAMP\"}")
  echo "$R" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4 >> "$TOKENS_FILE"
done
echo "registered $(wc -l < "$TOKENS_FILE") users"

FIRST_JWT=$(head -1 "$TOKENS_FILE")
AUTH="Authorization: Bearer $FIRST_JWT"
for i in 1 2 3; do
  curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" \
    -d "{\"title\":\"Load incident $i\",\"severity\":\"medium\",\"anomalyDescription\":\"load test\"}" > /dev/null
done

echo
echo "=== fire 150 concurrent requests spread across the 20 distinct principals (mix of"
echo "    live-monitor state + incident list) and check for failures ==="
# 150 concurrent connections spread across 20 principals comfortably exercises the raised
# Tomcat thread pool (400) and Hikari pool (20, refilled quickly since virtual threads
# hold connections only for the query itself) without hitting the PER-PRINCIPAL rate
# limiter (100/min/token), which is a deliberate abuse control, not a concurrency bug.
PIDS=()
TMP_DIR=$(mktemp -d)
mapfile -t TOKENS < "$TOKENS_FILE"
NTOKENS=${#TOKENS[@]}
for i in $(seq 1 150); do
  (
    TOK="${TOKENS[$((i % NTOKENS))]}"
    if [ $((i % 2)) -eq 0 ]; then
      STATUS=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/api/live/state" -H "Authorization: Bearer $TOK")
    else
      STATUS=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/api/incidents" -H "Authorization: Bearer $TOK")
    fi
    echo "$STATUS" > "$TMP_DIR/$i.status"
  ) &
  PIDS+=($!)
done
for pid in "${PIDS[@]}"; do wait "$pid"; done

TOTAL=0
OK=0
declare -A CODE_COUNTS
for f in "$TMP_DIR"/*.status; do
  TOTAL=$((TOTAL+1))
  CODE=$(cat "$f")
  CODE_COUNTS[$CODE]=$(( ${CODE_COUNTS[$CODE]:-0} + 1 ))
  if [ "$CODE" = "200" ]; then OK=$((OK+1)); fi
done
rm -rf "$TMP_DIR" "$TOKENS_FILE"

echo "requests: $TOTAL, 200 OK: $OK"
for code in "${!CODE_COUNTS[@]}"; do echo "  status $code: ${CODE_COUNTS[$code]}"; done
if [ "$OK" -eq "$TOTAL" ]; then
  echo "PASS: all 150 concurrent requests (across 20 principals) returned 200 (no pool exhaustion, no dropped connections)"
else
  echo "FAIL: only $OK/$TOTAL requests succeeded"
fi

echo
echo "=== check gateway logs for connection-timeout / pool-exhaustion warnings during the burst ==="
WARN_COUNT=$(docker logs aegisai-gateway --since 2m 2>&1 | grep -ciE 'connection is not available|pool exhausted|timeout waiting for connection' || true)
echo "warnings found: $WARN_COUNT"
[ "$WARN_COUNT" -eq 0 ] && echo "PASS: no Hikari pool-exhaustion warnings during the burst" || echo "FAIL: pool-exhaustion warnings present"

echo
echo "=== gateway still healthy after the burst ==="
curl -s "$BASE/actuator/health"
echo

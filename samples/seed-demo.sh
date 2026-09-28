#!/usr/bin/env bash
# Seed a demo organization with realistic incidents, resolve a subset (so MTTR / benchmark /
# RL training have signal), train the RL triage agent, and print a summary. Everything goes
# through the public API, so it is org-scoped and exercises the real endpoints.
#
# Usage:
#   BASE=http://localhost:8080 bash seed-demo.sh
#   (defaults to http://localhost:8080)
#
# Creates a fresh org each run so you can seed repeatedly without collisions.
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
JSON='Content-Type: application/json'
STAMP="$(date +%s)"
EMAIL="demo+${STAMP}@aegis.local"
PASSWORD="Demo#2026pass"
ORG="Demo Org ${STAMP}"

echo "==> Registering demo org: $ORG ($EMAIL)"
REG="$(curl -s -X POST "$BASE/api/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"fullName\":\"Demo Operator\",\"organizationName\":\"$ORG\"}")"
TOKEN="$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)"
if [ -z "$TOKEN" ]; then
  echo "FAILED to register/login. Response:"; echo "$REG"; exit 1
fi
AUTH="Authorization: Bearer $TOKEN"
echo "    logged in."

# create_incident <title> <severity> <anomalyDescription>  -> echoes new incident id
create_incident() {
  curl -s -X POST "$BASE/api/incidents" -H "$AUTH" -H "$JSON" \
    -d "{\"title\":\"$1\",\"severity\":\"$2\",\"anomalyDescription\":\"$3\"}" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
}

resolve_incident() {
  curl -s -X POST "$BASE/api/incidents/$1/resolve" -H "$AUTH" -H "$JSON" \
    -d "{\"resolutionNotes\":\"$2\"}" > /dev/null
}

echo "==> Creating incidents..."
# Database cluster
DB1=$(create_incident "Database connection pool exhausted" "critical" "order-service postgres connection pool exhausted, HikariPool timed out after 5000ms, too many clients")
DB2=$(create_incident "Slow database queries" "high" "database queries timing out, slow connection to primary, checkpoint too frequent")
DB3=$(create_incident "Postgres replica lag spike" "medium" "database read replica lag 45s, sql reads returning stale data")
# Memory
MEM1=$(create_incident "OOM killed recommendation service" "critical" "out of memory oom killer terminated java process, heap space exhausted 4Gi limit")
MEM2=$(create_incident "Memory leak in payment worker" "high" "memory usage climbing, gc overhead high, throughput degrading")
# Network / gateway
NET1=$(create_incident "Nginx 502 spike on auth" "high" "nginx 502 bad gateway, upstream auth-service no live upstreams, connection timeout")
NET2=$(create_incident "DNS resolution failures" "medium" "network dns resolution failing intermittently for internal services")
# Cache
CACHE1=$(create_incident "Redis eviction storm" "high" "redis cache eviction spike, maxmemory allkeys-lru evicting keys, latency 900ms")
CACHE2=$(create_incident "Redis connection i/o timeout" "medium" "redis read tcp i/o timeout, cache miss rate 96%")
# Disk
DISK1=$(create_incident "Disk full on primary node" "critical" "no space left on device, pg_wal write failed, disk usage 100%")
# Auth
AUTH1=$(create_incident "Login latency degradation" "medium" "auth service login latency high, token cache miss rate elevated")
# Payment
PAY1=$(create_incident "Checkout failures downstream" "high" "payment service checkout failing, downstream order-service unreachable")
# A couple of low-severity / noise incidents
LOW1=$(create_incident "Elevated 4xx on search" "low" "api gateway search endpoint 4xx rate slightly elevated")
LOW2=$(create_incident "Cron job overran window" "low" "nightly batch job overran maintenance window by 12 minutes")

echo "    created 14 incidents."

echo "==> Resolving a subset (gives MTTR / benchmark / RL training signal)..."
# Resolve fast-and-clean ones and a few slower ones so resolution-time varies.
resolve_incident "$DB2"   "Increased pool size 20->50, added pgbouncer; latency recovered."
resolve_incident "$NET1"  "Scaled auth-service 2->6 replicas; warmed token cache."
resolve_incident "$CACHE1" "Raised redis maxmemory, tuned eviction policy to volatile-lru."
resolve_incident "$DISK1" "Emergency logrotate + vacuum freed 12GB; added disk alerting at 80%."
resolve_incident "$LOW1"  "Transient; client retry storm subsided."
resolve_incident "$MEM2"  "Patched leak in payment worker, added heap dump on OOM."
echo "    resolved 6 incidents."

echo "==> Training the RL triage agent on resolved incidents..."
curl -s -X POST "$BASE/api/triage/train" -H "$AUTH" -H "$JSON" -d '{}'; echo

echo
echo "======================================================================"
echo "  Demo data seeded."
echo "  Login:    $EMAIL"
echo "  Password: $PASSWORD"
echo "  Org:      $ORG"
echo "----------------------------------------------------------------------"
echo "  Snapshot:"
echo -n "   health : "; curl -s "$BASE/api/insights/health-score" -H "$AUTH"; echo
echo -n "   bench  : "; curl -s "$BASE/api/insights/benchmark" -H "$AUTH"; echo
echo -n "   triage : "; curl -s "$BASE/api/triage/queue" -H "$AUTH" | head -c 300; echo
echo "======================================================================"
echo "  Open the UI, log in with the credentials above, and explore:"
echo "   Overview · Incidents · RL Triage · Insights · Copilot · Dependency Map"
echo "======================================================================"

#!/usr/bin/env bash
# Phase 1 auth verification: register -> me -> login -> unauthorized check.
# Run on the EC2: bash infra/scripts/verify-auth.sh
set -uo pipefail

BASE=http://localhost:8080
EMAIL="founder+$(date +%s)@example.com"

echo "=== REGISTER ($EMAIL) ==="
REG=$(curl -s -X POST "$BASE/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Ada Lovelace\",\"organizationName\":\"Acme SRE\"}")
echo "$REG"
TOKEN=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

echo
echo "=== ME (with token) ==="
curl -s "$BASE/api/auth/me" -H "Authorization: Bearer $TOKEN"
echo

echo
echo "=== LOGIN ==="
curl -s -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\"}"
echo

echo
echo "=== ME (no token, expect 401) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "$BASE/api/auth/me"

echo
echo "=== LOGIN wrong password (expect 401) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"wrongpass1\"}"

echo
echo "=== duplicate register (expect 409) ==="
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"fullName\":\"Dup\",\"organizationName\":\"Dup Org\"}"

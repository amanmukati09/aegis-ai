#!/usr/bin/env bash
# Phase 0 smoke test: verify all three services respond. Run on the EC2 after ec2-up.sh.
#   bash infra/scripts/smoke-test.sh
set -uo pipefail

pass=0
fail=0

check() {
  local name="$1" url="$2"
  if curl -fsS --max-time 5 "$url" >/dev/null 2>&1; then
    echo "PASS  $name  ($url)"
    pass=$((pass+1))
  else
    echo "FAIL  $name  ($url)"
    fail=$((fail+1))
  fi
}

check "gateway actuator" "http://localhost:8080/actuator/health"
check "gateway api"      "http://localhost:8080/api/health"
check "ml-service"       "http://localhost:8001/healthz"
check "ml models"        "http://localhost:8001/v1/models"
check "frontend"         "http://localhost:3000"

echo
echo "Passed: $pass  Failed: $fail"
[[ $fail -eq 0 ]] && echo "Phase 0 smoke test OK." || echo "Some checks failed — see 'docker compose ps' and logs."

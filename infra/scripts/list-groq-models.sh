#!/usr/bin/env bash
# List the Groq model IDs available to the configured key. Run on the EC2.
set -uo pipefail
K=$(grep '^GROQ_API_KEY=' ~/aegisai/.env | cut -d= -f2-)
curl -s https://api.groq.com/openai/v1/models -H "Authorization: Bearer $K" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print(m['id']) for m in d.get('data',[])]" 2>/dev/null \
  || echo "could not parse models (check key / network)"

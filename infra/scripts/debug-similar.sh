#!/usr/bin/env bash
# Inspect raw pgvector distance/score for the most recent org's incidents.
set -uo pipefail
CID=$(docker ps --format '{{.Names}}' | grep postgres | head -1)
docker exec -i "$CID" psql -U aegisai -d aegisai <<'SQL'
-- pick the newest org that has >=2 embedded incidents
WITH latest AS (
  SELECT org_id
  FROM incidents
  WHERE embedding IS NOT NULL
  GROUP BY org_id
  HAVING count(*) >= 2
  ORDER BY max(detected_at) DESC
  LIMIT 1
)
SELECT
  a.title AS a_title,
  b.title AS b_title,
  (a.embedding <=> b.embedding)               AS cosine_distance,
  (1 - (a.embedding <=> b.embedding))          AS score,
  pg_typeof(1 - (a.embedding <=> b.embedding)) AS score_type
FROM incidents a
JOIN incidents b ON a.org_id = b.org_id AND a.id <> b.id
WHERE a.org_id = (SELECT org_id FROM latest)
  AND a.embedding IS NOT NULL AND b.embedding IS NOT NULL
LIMIT 5;
SQL

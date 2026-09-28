#!/usr/bin/env bash
# Inspect stored embeddings + raw cosine distance to debug similar-incident scoring.
docker exec aegisai-postgres psql -U aegisai -d aegisai -t -c \
  "SELECT title, (embedding IS NULL) AS null_emb, vector_dims(embedding) FROM incidents WHERE embedding IS NOT NULL ORDER BY created_at DESC LIMIT 5;"
echo "--- pairwise distance between two db incidents ---"
docker exec aegisai-postgres psql -U aegisai -d aegisai -t -c \
  "WITH a AS (SELECT embedding FROM incidents WHERE title LIKE 'DB%' AND embedding IS NOT NULL LIMIT 1),
        b AS (SELECT embedding FROM incidents WHERE title LIKE 'Database%' AND embedding IS NOT NULL LIMIT 1)
   SELECT (a.embedding <=> b.embedding) AS cosine_distance FROM a, b;"

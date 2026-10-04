-- Give KB articles the same pgvector similarity capability incidents already have, so
-- Copilot's knowledge-base search can do real semantic retrieval instead of keyword-only
-- ILIKE matching. Additive; V1-V6 untouched.
ALTER TABLE kb_articles ADD COLUMN embedding vector(384);

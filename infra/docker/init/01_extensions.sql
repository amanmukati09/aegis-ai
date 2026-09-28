-- Ensure required extensions exist before Flyway runs.
-- (Flyway V1 also creates these defensively with IF NOT EXISTS.)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

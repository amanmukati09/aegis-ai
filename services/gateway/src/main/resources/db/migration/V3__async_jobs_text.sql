-- Store async job payload/result as text (JSON strings) rather than jsonb, so the JPA
-- mapping binds plain strings without a custom jsonb type handler. Additive; V1 untouched.
ALTER TABLE async_jobs ALTER COLUMN payload TYPE text USING payload::text;
ALTER TABLE async_jobs ALTER COLUMN result TYPE text USING result::text;

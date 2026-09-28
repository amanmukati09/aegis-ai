-- Store IP addresses as plain text rather than the Postgres `inet` type.
-- `inet` requires a special bind type; text keeps the JPA mapping simple and is
-- sufficient for audit/forwarded-for values (which may not be valid inet literals).
ALTER TABLE audit_logs ALTER COLUMN ip_address TYPE text USING ip_address::text;

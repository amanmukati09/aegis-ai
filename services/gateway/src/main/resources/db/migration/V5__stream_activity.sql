-- Track real ingestion activity on a stream so it stops being a decorative row: last
-- event time + a running event count, updated by POST /api/streams/{id}/events.
-- Additive; V1-V4 untouched.
ALTER TABLE stream_registrations ADD COLUMN last_event_at TIMESTAMPTZ;
ALTER TABLE stream_registrations ADD COLUMN event_count BIGINT NOT NULL DEFAULT 0;

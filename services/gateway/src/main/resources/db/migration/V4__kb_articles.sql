-- Knowledge base: articles auto-generated from resolved incidents (or authored manually).
-- Additive; V1-V3 untouched.
CREATE TABLE kb_articles (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    source_incident_id UUID REFERENCES incidents(id) ON DELETE SET NULL,
    title             VARCHAR(255) NOT NULL,
    category          VARCHAR(100),
    tags              TEXT,              -- comma-separated
    symptoms          TEXT,
    root_cause        TEXT,
    solution          TEXT,
    prevention        TEXT,
    difficulty        VARCHAR(20) NOT NULL DEFAULT 'Intermediate',
    created_by        UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kb_articles_org_id ON kb_articles(org_id);
CREATE INDEX idx_kb_articles_source_incident ON kb_articles(source_incident_id);

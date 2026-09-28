-- ============================================================
-- AegisAI V1 — initial schema (multi-tenant, UUID PKs)
-- Merges the hackathon data model with the multi-tenant/RBAC model.
-- Additive only: future features add V2+, never edit this file.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- pgvector: image built from pgvector/pgvector:pg16 provides this extension.
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------- Tenancy & identity ----------

CREATE TABLE organizations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    plan        VARCHAR(50)  NOT NULL DEFAULT 'free',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Roles: 'super_admin', 'org_admin', 'member'
CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID REFERENCES organizations(id) ON DELETE CASCADE,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255),                 -- null when OAuth-only
    full_name      VARCHAR(255) NOT NULL,
    role           VARCHAR(50)  NOT NULL DEFAULT 'member',
    auth_provider  VARCHAR(50)  NOT NULL DEFAULT 'local', -- local|google|github|microsoft
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_org_id ON users(org_id);
CREATE INDEX idx_users_email  ON users(email);

-- Per-user API keys (hashed; prefix shown for display)
CREATE TABLE api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    key_hash     VARCHAR(255) NOT NULL UNIQUE,
    key_prefix   VARCHAR(32)  NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_keys_user_id ON api_keys(user_id);

-- ---------- Streams ----------

CREATE TABLE stream_registrations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    source_type   VARCHAR(50)  NOT NULL DEFAULT 'http', -- http|kafka (adapter)
    kafka_topic   VARCHAR(255) UNIQUE,                  -- null unless kafka adapter
    metric_schema JSONB,
    status        VARCHAR(50)  NOT NULL DEFAULT 'active',
    model_version VARCHAR(100),
    last_drift_at TIMESTAMPTZ,
    created_by    UUID REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_stream_registrations_org_id ON stream_registrations(org_id);

-- ---------- Incidents ----------

CREATE TABLE incidents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    stream_id         UUID REFERENCES stream_registrations(id) ON DELETE SET NULL,
    user_id           UUID REFERENCES users(id),          -- creator/owner
    title             VARCHAR(500),
    raw_logs          TEXT,
    status            VARCHAR(50)  NOT NULL DEFAULT 'open',
    severity          VARCHAR(50),
    anomaly_score     NUMERIC(5,4),
    anomaly_description TEXT,
    anomaly_details   JSONB,
    root_cause        TEXT,
    causal_graph_id   VARCHAR(255),                       -- Neo4j graph id (optional adapter)
    remediation_action TEXT,
    remediation_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    rl_priority       INTEGER,
    rl_confidence     NUMERIC(5,4),
    embedding         vector(384),                        -- pgvector similarity
    assigned_to       UUID REFERENCES users(id),
    resolved_by       UUID REFERENCES users(id),
    resolution_notes  TEXT,
    detected_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_incidents_org_id       ON incidents(org_id);
CREATE INDEX idx_incidents_stream_id    ON incidents(stream_id);
CREATE INDEX idx_incidents_status       ON incidents(status);
CREATE INDEX idx_incidents_severity     ON incidents(severity);
CREATE INDEX idx_incidents_detected_at  ON incidents(detected_at DESC);

-- ---------- Chat / copilot ----------

CREATE TABLE chat_sessions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(255) NOT NULL DEFAULT 'New Incident Discussion',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_sessions_user_id ON chat_sessions(user_id);

CREATE TABLE chat_messages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL,      -- user|assistant|system
    content     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_messages_session_id ON chat_messages(session_id);

-- ---------- Workspaces ----------

CREATE TABLE workspaces (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (org_id, name)
);

CREATE TABLE workspace_members (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role         VARCHAR(50) NOT NULL DEFAULT 'member',
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, user_id)
);

-- ---------- Notifications ----------

CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(50),
    title       VARCHAR(255) NOT NULL,
    message     TEXT,
    is_read     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user_id ON notifications(user_id);

-- ---------- Audit ----------

CREATE TABLE audit_logs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID REFERENCES organizations(id) ON DELETE SET NULL,
    user_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    user_email    VARCHAR(255),
    action        VARCHAR(255) NOT NULL,
    resource_type VARCHAR(100),
    resource_id   VARCHAR(255),
    details       JSONB,
    ip_address    INET,
    user_agent    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_org_id     ON audit_logs(org_id);
CREATE INDEX idx_audit_logs_user_id    ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);

-- ---------- Alerts & webhooks ----------

CREATE TABLE alert_configs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    channel_type  VARCHAR(50) NOT NULL,   -- slack|teams|pagerduty|opsgenie|email|webhook
    config        JSONB NOT NULL,
    min_severity  VARCHAR(50) NOT NULL DEFAULT 'high',
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alert_configs_org_id ON alert_configs(org_id);

CREATE TABLE webhook_registrations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    stream_id    UUID REFERENCES stream_registrations(id) ON DELETE CASCADE,
    endpoint_url VARCHAR(500) NOT NULL,
    secret_hash  VARCHAR(255),
    events       TEXT[] NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Async jobs (replaces in-memory queue) ----------

CREATE TABLE async_jobs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID REFERENCES organizations(id) ON DELETE CASCADE,
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    type        VARCHAR(100) NOT NULL,
    status      VARCHAR(50)  NOT NULL DEFAULT 'pending', -- pending|running|completed|failed
    payload     JSONB,
    result      JSONB,
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_async_jobs_status ON async_jobs(status);
CREATE INDEX idx_async_jobs_user_id ON async_jobs(user_id);

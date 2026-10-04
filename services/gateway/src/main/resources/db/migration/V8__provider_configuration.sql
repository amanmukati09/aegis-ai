-- ============================================================
-- V8: AI provider flexibility — catalog, configurations, models, assignments, usage.
-- Additive only; V1-V7 untouched (per V1's "Additive only" convention).
-- ============================================================

-- ---------- Provider_Type catalog ----------
-- Data-driven, not code branches (Req 11.1). The `category` column lets this same
-- table later serve non-AI integrations (e.g. 'notification') without a schema
-- change (Req 9, 11.6).
CREATE TABLE provider_type_catalog (
    id                  VARCHAR(50) PRIMARY KEY,       -- 'groq', 'openai', 'anthropic', ...
    display_name        VARCHAR(100) NOT NULL,
    category            VARCHAR(50)  NOT NULL DEFAULT 'ai_provider',
    credential_fields    JSONB NOT NULL,                -- [{key,label,secret}]
    connection_fields    JSONB NOT NULL DEFAULT '[]',   -- [{key,label,secret,required}]
    default_models       JSONB NOT NULL DEFAULT '[]',   -- [{id,label}]
    adapter_dependency   VARCHAR(100),                  -- pip package name, null if stdlib/httpx-only
    adapter_class        VARCHAR(255) NOT NULL,         -- python import path
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Provider_Configuration ----------
-- org_id NULL means platform-wide fallback (Req 1.8).
CREATE TABLE provider_configurations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID REFERENCES organizations(id) ON DELETE CASCADE, -- NULL = platform-wide
    provider_type           VARCHAR(50) NOT NULL REFERENCES provider_type_catalog(id),
    display_name            VARCHAR(255) NOT NULL,
    encrypted_credentials   JSONB NOT NULL,             -- {field_key: base64(iv||ct||tag)}
    credential_hints        JSONB NOT NULL DEFAULT '{}', -- {field_key: "...ab12"} (masked, no decrypt needed)
    connection_settings     JSONB NOT NULL DEFAULT '{}', -- non-secret: base_url, region, deployment_name
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_by              UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_provider_configurations_org_id ON provider_configurations(org_id);
CREATE INDEX idx_provider_configurations_type   ON provider_configurations(provider_type);

-- ---------- Model_Entry ----------
CREATE TABLE model_entries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_configuration_id UUID NOT NULL REFERENCES provider_configurations(id) ON DELETE CASCADE,
    model_id                VARCHAR(255) NOT NULL,      -- e.g. 'claude-sonnet-4-5-20250929'
    label                   VARCHAR(255),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_configuration_id, model_id)
);
CREATE INDEX idx_model_entries_provider_config ON model_entries(provider_configuration_id);

-- ---------- Global_Default ----------
-- At most one per org (and one platform-wide, org_id NULL).
CREATE TABLE global_default_assignments (
    org_id                  UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    provider_configuration_id UUID NOT NULL REFERENCES provider_configurations(id) ON DELETE CASCADE,
    model_entry_id          UUID NOT NULL REFERENCES model_entries(id) ON DELETE CASCADE,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Platform-wide Global_Default uses a sentinel singleton row instead, to keep
-- org_id NOT NULL/PK semantics on global_default_assignments simple.
CREATE TABLE platform_default_assignment (
    singleton               BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    provider_configuration_id UUID NOT NULL REFERENCES provider_configurations(id) ON DELETE CASCADE,
    model_entry_id          UUID NOT NULL REFERENCES model_entries(id) ON DELETE CASCADE,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Feature_Model_Assignment ----------
-- One row per (org, feature) with an explicit override. Absence of a row for a
-- (org, feature) pair means "use Global_Default" (Req 5.2/5.5).
CREATE TABLE feature_model_assignments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    feature                 VARCHAR(50) NOT NULL,       -- Feature enum value
    provider_configuration_id UUID NOT NULL REFERENCES provider_configurations(id) ON DELETE CASCADE,
    model_entry_id          UUID NOT NULL REFERENCES model_entries(id) ON DELETE CASCADE,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, feature)
);

-- ---------- Priority_List ----------
-- Ordered failover candidates, scoped globally or per-feature, per org or platform-wide.
CREATE TABLE priority_list_entries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID REFERENCES organizations(id) ON DELETE CASCADE, -- NULL = platform-wide
    feature                 VARCHAR(50),                -- NULL = applies to '_global' (all features without a feature-specific list)
    provider_configuration_id UUID NOT NULL REFERENCES provider_configurations(id) ON DELETE CASCADE,
    model_entry_id          UUID NOT NULL REFERENCES model_entries(id) ON DELETE CASCADE,
    priority_order          INTEGER NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULLS NOT DISTINCT (pg15+): org_id and feature both use NULL to mean
    -- something specific (platform-wide; global scope), not "unknown" -- the
    -- default ANSI SQL behavior (every NULL distinct from every other NULL)
    -- would silently let multiple rows share the same (NULL, NULL, priority_order),
    -- defeating the one-pair-per-priority-slot guarantee for the platform-wide/
    -- global-scope case.
    UNIQUE NULLS NOT DISTINCT (org_id, feature, priority_order)
);
CREATE INDEX idx_priority_list_org_feature ON priority_list_entries(org_id, feature);

-- ---------- Usage_Record ----------
-- One row per LLM call attempt (success or exhausted failure, Req 6.1/6.2).
CREATE TABLE usage_records (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID REFERENCES organizations(id) ON DELETE SET NULL,
    user_id                 UUID REFERENCES users(id) ON DELETE SET NULL,
    provider_configuration_id UUID REFERENCES provider_configurations(id) ON DELETE SET NULL,
    model_entry_id          UUID REFERENCES model_entries(id) ON DELETE SET NULL,
    feature                 VARCHAR(50) NOT NULL,
    input_tokens            INTEGER,                    -- NULL for exhausted-failure records (Req 6.2)
    output_tokens           INTEGER,
    success                 BOOLEAN NOT NULL,
    served_pair_index       INTEGER,                    -- which Priority_List position served it (Req 4.6)
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Rollup strategy: no hot-path aggregation table write; usage_records is append-only
-- and cheap to write (one INSERT per call). Query-time aggregation uses these indexes:
CREATE INDEX idx_usage_records_org_created    ON usage_records(org_id, created_at);
CREATE INDEX idx_usage_records_user_created   ON usage_records(user_id, created_at);
CREATE INDEX idx_usage_records_provider_model ON usage_records(provider_configuration_id, model_entry_id);

-- ---------- Daily rollup ----------
-- Materialized as a summary table, refreshed incrementally (not on every write) to
-- keep the write path (one INSERT into usage_records) free of aggregation cost.
CREATE TABLE usage_daily_rollups (
    org_id        UUID REFERENCES organizations(id) ON DELETE CASCADE,
    rollup_date   DATE NOT NULL,
    input_tokens  BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    call_count    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (org_id, rollup_date)
);

-- ============================================================
-- Seed data: Provider_Type catalog (Req 2.1, 2.3, 3.3, 11.1)
-- adapter_class import paths follow app.providers.<name>_adapter.<ClassName>Adapter.
-- Groq and Ollama point at the adapter classes/files that already exist today under
-- their NEW names per design.md (tasks 8.2/8.3 rewrite groq_provider.py/ollama_provider.py
-- in place to expose GroqAdapter/OllamaAdapter from the same file paths).
-- All other adapter files do not exist yet; later tasks (per design.md's worked-example
-- extensibility pattern) add one adapter module per Provider_Type without touching this
-- schema or seed shape again.
-- ============================================================

INSERT INTO provider_type_catalog
    (id, display_name, category, credential_fields, connection_fields, default_models, adapter_dependency, adapter_class, enabled)
VALUES
(
    'groq',
    'Groq',
    'ai_provider',
    '[{"key": "api_key", "label": "API Key", "secret": true}]'::jsonb,
    '[]'::jsonb,
    '[
        {"id": "llama-3.3-70b-versatile", "label": "Llama 3.3 70B Versatile"},
        {"id": "llama-3.1-8b-instant", "label": "Llama 3.1 8B Instant"},
        {"id": "mixtral-8x7b-32768", "label": "Mixtral 8x7B"}
    ]'::jsonb,
    NULL,
    'app.providers.groq_provider.GroqAdapter',
    TRUE
),
(
    'openai',
    'OpenAI',
    'ai_provider',
    '[{"key": "api_key", "label": "API Key", "secret": true}]'::jsonb,
    '[{"key": "base_url", "label": "Base URL", "secret": false, "required": false}]'::jsonb,
    '[
        {"id": "gpt-4o", "label": "GPT-4o"},
        {"id": "gpt-4o-mini", "label": "GPT-4o Mini"},
        {"id": "gpt-4-turbo", "label": "GPT-4 Turbo"}
    ]'::jsonb,
    'langchain-openai',
    'app.providers.openai_adapter.OpenAIAdapter',
    TRUE
),
(
    'anthropic',
    'Anthropic',
    'ai_provider',
    '[{"key": "api_key", "label": "API Key", "secret": true}]'::jsonb,
    '[{"key": "base_url", "label": "Base URL", "secret": false, "required": false}]'::jsonb,
    '[
        {"id": "claude-sonnet-4-5-20250929", "label": "Claude Sonnet 4.5"},
        {"id": "claude-opus-4-1-20250805", "label": "Claude Opus 4.1"}
    ]'::jsonb,
    'langchain-anthropic',
    'app.providers.anthropic_adapter.AnthropicAdapter',
    TRUE
),
(
    'aws_bedrock',
    'AWS Bedrock',
    'ai_provider',
    '[
        {"key": "access_key_id", "label": "Access Key ID", "secret": true},
        {"key": "secret_access_key", "label": "Secret Access Key", "secret": true},
        {"key": "region", "label": "Region", "secret": false}
    ]'::jsonb,
    '[]'::jsonb,
    '[
        {"id": "anthropic.claude-3-5-sonnet-20241022-v2:0", "label": "Claude 3.5 Sonnet (Bedrock)"},
        {"id": "amazon.titan-text-premier-v1:0", "label": "Titan Text Premier"},
        {"id": "meta.llama3-1-70b-instruct-v1:0", "label": "Llama 3.1 70B Instruct"}
    ]'::jsonb,
    'boto3',
    'app.providers.bedrock_adapter.BedrockAdapter',
    TRUE
),
(
    'azure_openai',
    'Azure OpenAI',
    'ai_provider',
    '[{"key": "api_key", "label": "API Key", "secret": true}]'::jsonb,
    '[
        {"key": "endpoint", "label": "Endpoint URL", "secret": false, "required": true},
        {"key": "deployment_name", "label": "Deployment Name", "secret": false, "required": true},
        {"key": "api_version", "label": "API Version", "secret": false, "required": false}
    ]'::jsonb,
    '[
        {"id": "gpt-4o", "label": "GPT-4o (Azure deployment)"},
        {"id": "gpt-4o-mini", "label": "GPT-4o Mini (Azure deployment)"}
    ]'::jsonb,
    'langchain-openai',
    'app.providers.azure_openai_adapter.AzureOpenAIAdapter',
    TRUE
),
(
    'deepseek',
    'DeepSeek',
    'ai_provider',
    '[{"key": "api_key", "label": "API Key", "secret": true}]'::jsonb,
    '[{"key": "base_url", "label": "Base URL", "secret": false, "required": false}]'::jsonb,
    '[
        {"id": "deepseek-chat", "label": "DeepSeek Chat"},
        {"id": "deepseek-reasoner", "label": "DeepSeek Reasoner"}
    ]'::jsonb,
    'langchain-deepseek',
    'app.providers.deepseek_adapter.DeepSeekAdapter',
    TRUE
),
(
    'openrouter',
    'OpenRouter',
    'ai_provider',
    '[{"key": "api_key", "label": "API Key", "secret": true}]'::jsonb,
    '[{"key": "base_url", "label": "Base URL", "secret": false, "required": false}]'::jsonb,
    '[
        {"id": "meta-llama/llama-3.3-70b-instruct", "label": "Llama 3.3 70B Instruct (OpenRouter)"},
        {"id": "anthropic/claude-3.5-sonnet", "label": "Claude 3.5 Sonnet (OpenRouter)"},
        {"id": "openai/gpt-4o-mini", "label": "GPT-4o Mini (OpenRouter)"}
    ]'::jsonb,
    'langchain-openai',
    'app.providers.openrouter_adapter.OpenRouterAdapter',
    TRUE
),
(
    'xai',
    'xAI',
    'ai_provider',
    '[{"key": "api_key", "label": "API Key", "secret": true}]'::jsonb,
    '[{"key": "base_url", "label": "Base URL", "secret": false, "required": false}]'::jsonb,
    '[
        {"id": "grok-2-latest", "label": "Grok 2"},
        {"id": "grok-beta", "label": "Grok Beta"}
    ]'::jsonb,
    'langchain-xai',
    'app.providers.xai_adapter.XAIAdapter',
    TRUE
),
(
    'ollama',
    'Ollama',
    'ai_provider',
    '[]'::jsonb,
    '[{"key": "base_url", "label": "Base URL", "secret": false, "required": true}]'::jsonb,
    '[
        {"id": "llama3.1", "label": "Llama 3.1 (local)"},
        {"id": "mistral", "label": "Mistral (local)"}
    ]'::jsonb,
    NULL,
    'app.providers.ollama_provider.OllamaAdapter',
    TRUE
);

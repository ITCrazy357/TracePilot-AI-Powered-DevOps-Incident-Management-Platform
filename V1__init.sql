-- TracePilot | Initial PostgreSQL schema
-- Target: PostgreSQL 16+ and pgvector. UTF-8.
-- Run with Flyway as V1__init.sql after installing the pgvector extension.
-- Use a dedicated app DB account with least privilege. Do not persist raw GitHub logs or credentials.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    password_hash TEXT NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(12) NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(workspace_id,user_id)
);
CREATE INDEX ix_workspace_members_user ON workspace_members(user_id);

CREATE TABLE github_installations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    github_installation_id BIGINT NOT NULL UNIQUE,
    account_login VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED','REMOVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_gh_installations_workspace ON github_installations(workspace_id);

CREATE TABLE repositories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    installation_id UUID NOT NULL REFERENCES github_installations(id),
    github_repository_id BIGINT NOT NULL,
    full_name VARCHAR(300) NOT NULL,
    default_branch VARCHAR(255) NOT NULL DEFAULT 'main',
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(workspace_id,github_repository_id)
);
CREATE INDEX ix_repositories_installation ON repositories(installation_id);

-- Inbox deduplication: GitHub's X-GitHub-Delivery is unique per delivery.
-- Save only event metadata and a hash. Never save unfiltered webhook payloads here.
CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    github_delivery_id VARCHAR(128) NOT NULL UNIQUE,
    installation_id UUID REFERENCES github_installations(id),
    repository_id UUID REFERENCES repositories(id),
    event_type VARCHAR(100) NOT NULL,
    event_action VARCHAR(100),
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED'
        CHECK(status IN ('RECEIVED','PROCESSING','PROCESSED','FAILED','IGNORED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    last_error TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
CREATE INDEX ix_webhooks_status ON webhook_deliveries(status,received_at);

CREATE TABLE workflow_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id UUID NOT NULL REFERENCES repositories(id),
    github_run_id BIGINT NOT NULL,
    run_attempt INTEGER NOT NULL DEFAULT 1 CHECK(run_attempt >= 1),
    github_workflow_id BIGINT,
    workflow_name VARCHAR(255) NOT NULL,
    head_branch VARCHAR(255),
    head_sha VARCHAR(64),
    status VARCHAR(40) NOT NULL,
    conclusion VARCHAR(40),
    html_url TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(repository_id,github_run_id,run_attempt)
);
CREATE INDEX ix_workflow_runs_repo_created ON workflow_runs(repository_id,created_at DESC);

CREATE TABLE workflow_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_run_id UUID NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    github_job_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(40) NOT NULL,
    conclusion VARCHAR(40),
    html_url TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE(workflow_run_id,github_job_id)
);

-- MVP: maximum one incident per run attempt. Can expand to per-job incidents later.
CREATE TABLE incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    repository_id UUID NOT NULL REFERENCES repositories(id),
    workflow_run_id UUID NOT NULL UNIQUE REFERENCES workflow_runs(id),
    failed_job_id UUID REFERENCES workflow_jobs(id),
    assigned_to UUID REFERENCES users(id),
    title VARCHAR(300) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK(status IN ('OPEN','INVESTIGATING','RESOLVED','IGNORED')),
    severity VARCHAR(10) NOT NULL DEFAULT 'MEDIUM'
        CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    error_fingerprint CHAR(64),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_incidents_workspace_status ON incidents(workspace_id,status,created_at DESC);
CREATE INDEX ix_incidents_fingerprint ON incidents(workspace_id,error_fingerprint);

-- Each text chunk is masked/redacted BEFORE insertion. Limit input size in the application.
CREATE TABLE incident_log_snippets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    workflow_job_id UUID REFERENCES workflow_jobs(id),
    chunk_index INTEGER NOT NULL CHECK(chunk_index >= 0),
    content TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    source_url TEXT,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(incident_id,chunk_index)
);

CREATE TABLE ai_analyses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    provider VARCHAR(80) NOT NULL,
    model VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    summary TEXT,
    suspected_root_cause TEXT,
    suggested_fix TEXT,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_analyses_incident ON ai_analyses(incident_id,created_at DESC);

-- Only approved knowledge gets embedded and retrieved by RAG.
CREATE TABLE knowledge_articles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    source_incident_id UUID REFERENCES incidents(id),
    title VARCHAR(300) NOT NULL,
    root_cause TEXT NOT NULL,
    resolution TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK(status IN ('DRAFT','APPROVED','ARCHIVED')),
    created_by UUID NOT NULL REFERENCES users(id),
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_kb_workspace_status ON knowledge_articles(workspace_id,status,created_at DESC);
CREATE INDEX ix_kb_fulltext ON knowledge_articles USING gin (
    to_tsvector('english', coalesce(title,'') || ' ' || coalesce(root_cause,'') || ' ' || coalesce(resolution,''))
);

-- Example dimension: 1536. Choose a single compatible embedding model and dimension.
CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    article_id UUID NOT NULL REFERENCES knowledge_articles(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK(chunk_index >= 0),
    content TEXT NOT NULL,
    embedding vector(1536) NOT NULL,
    embedding_model VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(article_id,chunk_index)
);
CREATE INDEX ix_knowledge_chunks_workspace ON knowledge_chunks(workspace_id);
CREATE INDEX ix_knowledge_chunks_hnsw ON knowledge_chunks
    USING hnsw (embedding vector_cosine_ops);

-- Exactly one source per AI evidence record: a sanitized log chunk or an approved KB chunk.
CREATE TABLE analysis_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id UUID NOT NULL REFERENCES ai_analyses(id) ON DELETE CASCADE,
    log_snippet_id UUID REFERENCES incident_log_snippets(id),
    knowledge_chunk_id UUID REFERENCES knowledge_chunks(id),
    similarity_score DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((log_snippet_id IS NOT NULL) <> (knowledge_chunk_id IS NOT NULL))
);
CREATE INDEX ix_evidence_analysis ON analysis_evidence(analysis_id);

CREATE TABLE ai_usage_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    analysis_id UUID REFERENCES ai_analyses(id),
    operation VARCHAR(16) NOT NULL CHECK(operation IN ('CHAT','EMBEDDING')),
    provider VARCHAR(80) NOT NULL,
    model VARCHAR(120) NOT NULL,
    input_tokens INTEGER CHECK(input_tokens >= 0),
    output_tokens INTEGER CHECK(output_tokens >= 0),
    estimated_cost_usd NUMERIC(14,8) CHECK(estimated_cost_usd >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_usage_workspace_created ON ai_usage_events(workspace_id,created_at DESC);

CREATE TABLE incident_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id UUID NOT NULL REFERENCES ai_analyses(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    helpful BOOLEAN NOT NULL,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(analysis_id,user_id)
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    user_id UUID NOT NULL REFERENCES users(id),
    incident_id UUID REFERENCES incidents(id),
    type VARCHAR(40) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_unread ON notifications(user_id,created_at DESC) WHERE read_at IS NULL;

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_workspace_created ON audit_logs(workspace_id,created_at DESC);

-- Advanced reliability: transactional outbox. Poll/publish to RabbitMQ, mark PUBLISHED.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK(status IN ('PENDING','PUBLISHED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_outbox_ready ON outbox_events(status,available_at);

-- SECURITY NOTES:
-- 1. Verify X-Hub-Signature-256 against the RAW request body before processing GitHub events.
-- 2. Validate workspace ownership on every FK association in application services.
--    For stronger tenant isolation, add composite tenant FKs and/or PostgreSQL RLS.
-- 3. Persist no GitHub App private keys, installation access tokens or API keys in these tables.
-- 4. Only retrieve APPROVED knowledge_chunks of the requesting workspace.
-- 5. Set updated_at in application services or DB trigger (this migration does not auto-update them).
-- 6. Rebuild vectors/index if changing embedding model or dimensionality.

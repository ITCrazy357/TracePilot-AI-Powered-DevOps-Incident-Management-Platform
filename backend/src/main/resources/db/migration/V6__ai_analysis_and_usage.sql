CREATE TABLE ai_analyses (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    incident_id UUID NOT NULL,
    requested_by UUID REFERENCES users(id),
    request_id UUID NOT NULL,
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('AUTOMATIC', 'MANUAL')),
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    input_sha256 VARCHAR(64) NOT NULL CHECK (length(input_sha256) = 64),
    summary TEXT,
    suspected_root_cause TEXT,
    suggested_fix TEXT,
    evidence JSONB NOT NULL DEFAULT '[]',
    error_code VARCHAR(40),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (incident_id, workspace_id) REFERENCES incidents(id, workspace_id),
    UNIQUE (workspace_id, request_id),
    UNIQUE (id, workspace_id),
    CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED' OR
        (summary IS NOT NULL AND suspected_root_cause IS NOT NULL AND suggested_fix IS NOT NULL))
);
CREATE INDEX ix_ai_analyses_incident_created ON ai_analyses(incident_id, created_at DESC, id);
CREATE UNIQUE INDEX uq_ai_analyses_automatic ON ai_analyses(incident_id) WHERE origin = 'AUTOMATIC';
CREATE UNIQUE INDEX uq_ai_analyses_active ON ai_analyses(incident_id) WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE ai_usage_events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    analysis_id UUID NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    operation VARCHAR(16) NOT NULL DEFAULT 'CHAT' CHECK (operation = 'CHAT'),
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('UNKNOWN', 'SUCCEEDED', 'FAILED')),
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    input_price_per_million NUMERIC(16,8) CHECK (input_price_per_million >= 0),
    output_price_per_million NUMERIC(16,8) CHECK (output_price_per_million >= 0),
    estimated_cost_usd NUMERIC(16,8) CHECK (estimated_cost_usd >= 0),
    duration_ms BIGINT CHECK (duration_ms >= 0),
    error_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (analysis_id, workspace_id) REFERENCES ai_analyses(id, workspace_id),
    UNIQUE (analysis_id, attempt)
);
CREATE INDEX ix_ai_usage_workspace_created ON ai_usage_events(workspace_id, created_at);

CREATE UNIQUE INDEX uq_ai_analysis_outbox ON outbox_events ((payload->>'analysisId'))
    WHERE event_type = 'AI_ANALYSIS';

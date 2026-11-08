CREATE TABLE workflow_runs (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES repositories(id),
    github_run_id BIGINT NOT NULL CHECK (github_run_id > 0),
    run_attempt INTEGER NOT NULL CHECK (run_attempt > 0),
    github_workflow_id BIGINT NOT NULL CHECK (github_workflow_id > 0),
    workflow_name VARCHAR(255) NOT NULL,
    head_branch VARCHAR(255),
    head_sha VARCHAR(64),
    status VARCHAR(40) NOT NULL,
    conclusion VARCHAR(40),
    html_url TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (repository_id, github_run_id, run_attempt)
);
CREATE INDEX ix_workflow_runs_repository_created ON workflow_runs(repository_id, created_at DESC, id);

CREATE TABLE workflow_jobs (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL REFERENCES workflow_runs(id),
    github_job_id BIGINT NOT NULL CHECK (github_job_id > 0),
    name VARCHAR(255) NOT NULL,
    status VARCHAR(40) NOT NULL,
    conclusion VARCHAR(40),
    html_url TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (workflow_run_id, github_job_id)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    source_delivery_id UUID UNIQUE REFERENCES webhook_deliveries(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL,
    publish_token UUID,
    publish_lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (processing_status IN ('PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'FAILED', 'IGNORED')),
    processing_attempts INTEGER NOT NULL DEFAULT 0 CHECK (processing_attempts >= 0),
    processing_token UUID,
    processing_lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(40),
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_outbox_pending ON outbox_events(available_at, created_at)
    WHERE status IN ('PENDING', 'PUBLISHING');
CREATE INDEX ix_outbox_aggregate_created ON outbox_events(aggregate_id, created_at DESC, id);
CREATE INDEX ix_outbox_processing_leases ON outbox_events(processing_lease_until)
    WHERE processing_status = 'RUNNING';
CREATE INDEX ix_outbox_unconsumed ON outbox_events(published_at)
    WHERE status = 'PUBLISHED' AND processing_status IN ('PENDING', 'RETRY');

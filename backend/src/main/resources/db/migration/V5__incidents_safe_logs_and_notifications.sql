ALTER TABLE repositories ADD CONSTRAINT uq_repositories_id_workspace UNIQUE (id, workspace_id);
ALTER TABLE workflow_runs ADD CONSTRAINT uq_workflow_runs_id_repository UNIQUE (id, repository_id);
ALTER TABLE workflow_jobs ADD CONSTRAINT uq_workflow_jobs_id_run UNIQUE (id, workflow_run_id);

CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    repository_id UUID NOT NULL,
    workflow_run_id UUID NOT NULL UNIQUE,
    failed_job_id UUID,
    assigned_to UUID REFERENCES users(id),
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'IGNORED')),
    severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    error_fingerprint VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (repository_id, workspace_id) REFERENCES repositories(id, workspace_id),
    FOREIGN KEY (workflow_run_id, repository_id) REFERENCES workflow_runs(id, repository_id),
    FOREIGN KEY (failed_job_id, workflow_run_id) REFERENCES workflow_jobs(id, workflow_run_id),
    UNIQUE (id, workspace_id),
    UNIQUE (id, failed_job_id),
    CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);
CREATE INDEX ix_incidents_workspace_detected ON incidents(workspace_id, detected_at DESC, id);

CREATE TABLE incident_log_snippets (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents(id),
    workflow_job_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL CHECK (length(content) <= 4096),
    content_sha256 VARCHAR(64) NOT NULL CHECK (length(content_sha256) = 64),
    captured_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (incident_id, workflow_job_id) REFERENCES incidents(id, failed_job_id),
    UNIQUE (incident_id, chunk_index)
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    sequence_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    user_id UUID NOT NULL REFERENCES users(id),
    incident_id UUID NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(255) NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (incident_id, workspace_id) REFERENCES incidents(id, workspace_id)
);
CREATE INDEX ix_notifications_recipient_sequence ON notifications(workspace_id, user_id, sequence_number);

CREATE UNIQUE INDEX uq_incident_log_capture ON outbox_events ((payload->>'incidentId'))
    WHERE event_type = 'INCIDENT_LOG_CAPTURE';

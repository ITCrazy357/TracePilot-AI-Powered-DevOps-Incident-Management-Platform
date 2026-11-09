CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(40) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id UUID,
    metadata JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object'),
    request_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_audit_workspace_created ON audit_logs(workspace_id, created_at DESC, id);

CREATE TABLE rate_limit_buckets (
    scope VARCHAR(32) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    bucket_start TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (scope, key_hash, bucket_start)
);
CREATE INDEX ix_rate_limit_expiry ON rate_limit_buckets(expires_at);

ALTER TABLE outbox_events
    ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    ADD COLUMN total_processing_attempts INTEGER NOT NULL DEFAULT 0 CHECK (total_processing_attempts >= 0);
UPDATE outbox_events SET total_processing_attempts = processing_attempts;

-- Used by backlog metrics; completed history does not need to be scanned on every sample.
CREATE INDEX ix_outbox_backlog ON outbox_events(event_type, processing_status, created_at)
    WHERE processing_status IN ('PENDING', 'RUNNING', 'RETRY', 'FAILED');

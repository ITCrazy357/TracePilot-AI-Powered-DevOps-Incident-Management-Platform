CREATE TABLE github_installations (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    github_installation_id BIGINT NOT NULL UNIQUE CHECK (github_installation_id > 0),
    account_login VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, workspace_id)
);
CREATE INDEX ix_github_installations_workspace ON github_installations(workspace_id);

CREATE TABLE repositories (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    installation_id UUID NOT NULL,
    github_repository_id BIGINT NOT NULL CHECK (github_repository_id > 0),
    full_name VARCHAR(300) NOT NULL,
    -- Selection webhooks may omit this field; a REST sync supplies the real value.
    default_branch VARCHAR(255),
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (installation_id, workspace_id) REFERENCES github_installations(id, workspace_id),
    UNIQUE (workspace_id, github_repository_id)
);
CREATE INDEX ix_repositories_installation ON repositories(installation_id);
CREATE INDEX ix_repositories_workspace_name ON repositories(workspace_id, full_name, id);

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    github_delivery_id VARCHAR(128) NOT NULL UNIQUE,
    installation_id UUID REFERENCES github_installations(id),
    event_type VARCHAR(100) NOT NULL,
    event_action VARCHAR(100),
    payload_sha256 VARCHAR(64) NOT NULL CHECK (length(payload_sha256) = 64),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED')),
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ
);
CREATE INDEX ix_webhook_deliveries_received ON webhook_deliveries(received_at);

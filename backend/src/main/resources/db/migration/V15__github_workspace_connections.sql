ALTER TABLE github_installations DROP CONSTRAINT github_installations_github_installation_id_key;
ALTER TABLE github_installations
    ADD CONSTRAINT uq_github_workspace_installation UNIQUE (workspace_id, github_installation_id),
    ADD CONSTRAINT uq_github_workspace_connection UNIQUE (workspace_id);
ALTER TABLE repositories ADD COLUMN tracking_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE repositories ADD CONSTRAINT ck_repository_tracking_active CHECK (tracking_enabled OR NOT is_active);
ALTER TABLE outbox_events DROP CONSTRAINT outbox_events_source_delivery_id_key;
ALTER TABLE outbox_events ADD CONSTRAINT uq_outbox_delivery_repository UNIQUE (source_delivery_id, aggregate_id);

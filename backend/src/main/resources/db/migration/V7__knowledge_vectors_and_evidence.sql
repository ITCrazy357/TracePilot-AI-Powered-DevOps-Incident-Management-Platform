CREATE TABLE knowledge_articles (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    source_incident_id UUID,
    title VARCHAR(160) NOT NULL,
    root_cause TEXT NOT NULL CHECK (length(root_cause) <= 3000),
    resolution TEXT NOT NULL CHECK (length(resolution) <= 6000),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'ARCHIVED')),
    index_status VARCHAR(16) NOT NULL DEFAULT 'NONE' CHECK (index_status IN ('NONE', 'RUNNING', 'READY', 'FAILED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    content_version BIGINT NOT NULL DEFAULT 0 CHECK (content_version >= 0),
    created_by UUID NOT NULL REFERENCES users(id),
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    index_token UUID,
    index_lease_until TIMESTAMPTZ,
    index_error_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (source_incident_id, workspace_id) REFERENCES incidents(id, workspace_id),
    UNIQUE (id, workspace_id),
    CHECK (status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CHECK ((index_status = 'RUNNING') = (index_token IS NOT NULL AND index_lease_until IS NOT NULL))
);
CREATE INDEX ix_knowledge_workspace_updated ON knowledge_articles(workspace_id, updated_at DESC, id);

CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    article_id UUID NOT NULL,
    article_version BIGINT NOT NULL,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL CHECK (length(content) <= 1200),
    content_sha256 VARCHAR(64) NOT NULL CHECK (length(content_sha256) = 64),
    embedding VECTOR(1536) NOT NULL CHECK (vector_norm(embedding) > 0),
    embedding_model VARCHAR(120) NOT NULL CHECK (embedding_model = 'text-embedding-3-small'),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (article_id, workspace_id) REFERENCES knowledge_articles(id, workspace_id),
    UNIQUE (article_id, article_version, chunk_index),
    UNIQUE (id, workspace_id)
);
CREATE INDEX ix_knowledge_chunks_workspace_article ON knowledge_chunks(workspace_id, article_id, article_version);
-- Used by the explicit APPROXIMATE search mode; EXACT remains the default for selective tenant filters.
CREATE INDEX ix_knowledge_chunks_cosine ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);

ALTER TABLE ai_analyses ADD CONSTRAINT uq_analysis_id_workspace_incident UNIQUE (id, workspace_id, incident_id);
ALTER TABLE incident_log_snippets ADD CONSTRAINT uq_log_snippet_id_incident UNIQUE (id, incident_id);
CREATE TABLE analysis_evidence (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    incident_id UUID NOT NULL,
    log_snippet_id UUID,
    knowledge_chunk_id UUID,
    quote TEXT NOT NULL CHECK (length(quote) BETWEEN 8 AND 500),
    source_sha256 VARCHAR(64) NOT NULL CHECK (length(source_sha256) = 64),
    similarity_score DOUBLE PRECISION CHECK (similarity_score BETWEEN -1 AND 1),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (analysis_id, workspace_id, incident_id) REFERENCES ai_analyses(id, workspace_id, incident_id),
    FOREIGN KEY (log_snippet_id, incident_id) REFERENCES incident_log_snippets(id, incident_id),
    FOREIGN KEY (knowledge_chunk_id, workspace_id) REFERENCES knowledge_chunks(id, workspace_id),
    CHECK (num_nonnulls(log_snippet_id, knowledge_chunk_id) = 1)
);
CREATE INDEX ix_analysis_evidence_analysis ON analysis_evidence(analysis_id, created_at, id);

ALTER TABLE ai_usage_events ALTER COLUMN analysis_id DROP NOT NULL;
ALTER TABLE ai_usage_events DROP CONSTRAINT ai_usage_events_operation_check;
ALTER TABLE ai_usage_events ADD CONSTRAINT ai_usage_operation CHECK (operation IN ('CHAT', 'EMBEDDING'));
ALTER TABLE ai_usage_events DROP CONSTRAINT ai_usage_events_analysis_id_attempt_key;
ALTER TABLE ai_usage_events ADD CONSTRAINT uq_ai_usage_analysis_attempt_operation UNIQUE (analysis_id, attempt, operation);
ALTER TABLE ai_usage_events ADD COLUMN knowledge_article_id UUID;
ALTER TABLE ai_usage_events ADD CONSTRAINT fk_ai_usage_article_workspace
    FOREIGN KEY (knowledge_article_id, workspace_id) REFERENCES knowledge_articles(id, workspace_id);
ALTER TABLE ai_usage_events ADD CONSTRAINT ck_chat_requires_analysis CHECK (operation <> 'CHAT' OR analysis_id IS NOT NULL);

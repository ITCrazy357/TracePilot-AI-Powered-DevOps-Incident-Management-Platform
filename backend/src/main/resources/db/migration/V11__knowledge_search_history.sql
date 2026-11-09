CREATE TABLE knowledge_search_history (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    user_id UUID NOT NULL REFERENCES users(id),
    query TEXT NOT NULL CHECK (length(query) BETWEEN 1 AND 2000),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('EXACT', 'APPROXIMATE', 'HYBRID')),
    top_k INTEGER NOT NULL CHECK (top_k BETWEEN 1 AND 5),
    result_count INTEGER NOT NULL CHECK (result_count BETWEEN 0 AND 5),
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_search_history_owner ON knowledge_search_history(workspace_id, user_id, created_at DESC, id);

CREATE TABLE incident_feedback (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    incident_id UUID NOT NULL,
    analysis_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    helpful BOOLEAN NOT NULL,
    reason VARCHAR(40) NOT NULL,
    comment TEXT,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (analysis_id, workspace_id, incident_id)
        REFERENCES ai_analyses(id, workspace_id, incident_id),
    UNIQUE (analysis_id, user_id),
    CHECK (comment IS NULL OR char_length(comment) <= 2000),
    CHECK (
        (helpful AND reason IN ('CORRECT_DIAGNOSIS', 'ACTIONABLE_FIX', 'USEFUL_EVIDENCE', 'OTHER'))
        OR (NOT helpful AND reason IN
            ('INCORRECT_DIAGNOSIS', 'INSUFFICIENT_EVIDENCE', 'UNSAFE_SUGGESTION', 'NOT_A_REAL_INCIDENT', 'OTHER'))
    )
);

-- Dashboard cohort and feedback joins are scoped to a workspace and a bounded creation window.
CREATE INDEX idx_ai_analyses_workspace_created ON ai_analyses(workspace_id, created_at);
CREATE INDEX idx_feedback_workspace_analysis ON incident_feedback(workspace_id, analysis_id);

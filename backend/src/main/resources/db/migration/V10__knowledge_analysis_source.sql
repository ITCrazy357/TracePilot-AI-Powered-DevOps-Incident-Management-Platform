ALTER TABLE knowledge_articles ADD COLUMN source_analysis_id UUID;
ALTER TABLE knowledge_articles ADD CONSTRAINT fk_knowledge_analysis_source
    FOREIGN KEY (source_analysis_id, workspace_id, source_incident_id)
    REFERENCES ai_analyses(id, workspace_id, incident_id);
ALTER TABLE knowledge_articles ADD CONSTRAINT ck_knowledge_analysis_incident
    CHECK (source_analysis_id IS NULL OR source_incident_id IS NOT NULL);

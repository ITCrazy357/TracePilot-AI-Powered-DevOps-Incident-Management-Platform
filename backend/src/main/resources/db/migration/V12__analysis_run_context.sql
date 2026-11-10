ALTER TABLE ai_analyses ADD COLUMN run_context TEXT;
ALTER TABLE ai_analyses ADD CONSTRAINT ck_analysis_run_context_length
    CHECK (run_context IS NULL OR length(run_context) <= 4000);

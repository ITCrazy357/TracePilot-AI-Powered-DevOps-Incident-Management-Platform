ALTER TABLE ai_analyses
    ADD COLUMN assessment_status VARCHAR(32),
    ADD COLUMN missing_information JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN verification_steps JSONB NOT NULL DEFAULT '[]';
ALTER TABLE ai_analyses
    ADD CONSTRAINT ck_analysis_assessment_status CHECK (
        assessment_status IN ('HYPOTHESIS', 'INSUFFICIENT_EVIDENCE', 'NO_FAILURE_INDICATED')),
    ADD CONSTRAINT ck_analysis_missing_information CHECK (jsonb_typeof(missing_information) = 'array'),
    ADD CONSTRAINT ck_analysis_verification_steps CHECK (jsonb_typeof(verification_steps) = 'array');

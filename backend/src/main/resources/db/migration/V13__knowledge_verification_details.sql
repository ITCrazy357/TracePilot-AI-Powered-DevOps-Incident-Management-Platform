ALTER TABLE knowledge_articles
    ADD COLUMN symptoms TEXT NOT NULL DEFAULT '',
    ADD COLUMN applicability TEXT NOT NULL DEFAULT '',
    ADD COLUMN verification TEXT NOT NULL DEFAULT '';
ALTER TABLE knowledge_articles
    ADD CONSTRAINT ck_knowledge_symptoms_length CHECK (length(symptoms) <= 1000),
    ADD CONSTRAINT ck_knowledge_applicability_length CHECK (length(applicability) <= 1000),
    ADD CONSTRAINT ck_knowledge_verification_length CHECK (length(verification) <= 1500);

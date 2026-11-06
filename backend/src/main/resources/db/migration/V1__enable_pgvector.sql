-- Phase 00: infrastructure only; add business tables in subsequent migrations.
-- The local pgvector image includes the extension binaries.
CREATE EXTENSION IF NOT EXISTS vector;

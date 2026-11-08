package com.tracepilot.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.tracepilot.analysis.AnalysisDtos;
import com.tracepilot.analysis.AnalysisFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeText text;

    public EvidenceRepository(JdbcTemplate jdbcTemplate, KnowledgeText text) {
        this.jdbcTemplate = jdbcTemplate;
        this.text = text;
    }

    public List<KnowledgeDtos.Evidence> list(UUID analysisId) {
        return jdbcTemplate.query("""
            SELECT e.*, k.article_id, k.article_version, l.chunk_index AS log_chunk_index
            FROM analysis_evidence e LEFT JOIN knowledge_chunks k ON k.id = e.knowledge_chunk_id
            LEFT JOIN incident_log_snippets l ON l.id = e.log_snippet_id
            WHERE e.analysis_id = ? ORDER BY e.created_at, e.id
            """, (row, number) -> new KnowledgeDtos.Evidence(row.getObject("id", UUID.class),
            row.getObject("log_snippet_id") == null ? "KNOWLEDGE" : "LOG",
            row.getObject("log_snippet_id", UUID.class), row.getObject("log_chunk_index", Integer.class),
            row.getObject("knowledge_chunk_id", UUID.class), row.getObject("article_id", UUID.class),
            row.getObject("article_version", Long.class),
            row.getString("quote"), row.getString("source_sha256"), row.getObject("similarity_score", Double.class)), analysisId);
    }

    public void save(AnalysisDtos.AnalysisWork work, AnalysisDtos.Report report, RagContext context, Instant now) {
        var logs = jdbcTemplate.query("""
            SELECT id, content FROM incident_log_snippets WHERE incident_id = ? ORDER BY chunk_index
            """, (row, number) -> new KnowledgeDtos.LogSource(row.getObject("id", UUID.class), row.getString("content")),
            work.analysis().incidentId());
        for (var quote : report.evidence()) {
            var log = logs.stream().filter(source -> context.log().contains(quote) && source.content().contains(quote))
                .findFirst();
            if (log.isPresent()) {
                insert(work, log.orElseThrow().id(), null, quote, text.sha256(log.orElseThrow().content()), null, now);
                continue;
            }
            var knowledge = context.knowledge().stream().filter(source -> source.content().contains(quote)).findFirst()
                .orElseThrow(() -> new AnalysisFailure("INVALID_AI_EVIDENCE", true));
            insert(work, null, knowledge.id(), quote, knowledge.contentSha256(), knowledge.similarity(), now);
        }
    }

    private void insert(AnalysisDtos.AnalysisWork work, UUID logId, UUID knowledgeId, String quote,
                        String sha256, Double similarity, Instant now) {
        jdbcTemplate.update("""
            INSERT INTO analysis_evidence (id, analysis_id, workspace_id, incident_id, log_snippet_id,
                knowledge_chunk_id, quote, source_sha256, similarity_score, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), work.analysis().id(), work.workspaceId(), work.analysis().incidentId(),
            logId, knowledgeId, quote, sha256, similarity, Timestamp.from(now));
    }
}

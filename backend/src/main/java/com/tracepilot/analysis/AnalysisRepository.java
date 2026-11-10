package com.tracepilot.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AnalysisRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AnalysisDtos.AnalysisPage list(UUID workspaceId, UUID incidentId, int page, int size) {
        var items = jdbcTemplate.query("""
            SELECT * FROM ai_analyses WHERE workspace_id = ? AND incident_id = ?
            ORDER BY created_at DESC, id LIMIT ? OFFSET ?
            """, (row, number) -> response(row), workspaceId, incidentId, size, (long) page * size);
        long total = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_analyses WHERE workspace_id = ? AND incident_id = ?",
            Long.class, workspaceId, incidentId);
        return new AnalysisDtos.AnalysisPage(items, page, size, total);
    }

    public Optional<AnalysisDtos.AnalysisWork> find(UUID id) {
        return jdbcTemplate.query("""
            SELECT a.*, i.repository_id, (r.is_active AND g.status = 'ACTIVE') AS source_active
            FROM ai_analyses a JOIN incidents i ON i.id = a.incident_id
            JOIN repositories r ON r.id = i.repository_id
            JOIN github_installations g ON g.id = r.installation_id WHERE a.id = ?
            """, (row, number) -> new AnalysisDtos.AnalysisWork(response(row), row.getObject("workspace_id", UUID.class),
            row.getObject("repository_id", UUID.class), row.getObject("requested_by", UUID.class),
            row.getString("input_sha256"), row.getBoolean("source_active")), id).stream().findFirst();
    }

    public Optional<AnalysisDtos.AnalysisResponse> findRequest(UUID workspaceId, UUID requestId) {
        return jdbcTemplate.query("SELECT * FROM ai_analyses WHERE workspace_id = ? AND request_id = ?",
            (row, number) -> response(row), workspaceId, requestId).stream().findFirst();
    }

    public boolean hasAutomatic(UUID incidentId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM ai_analyses WHERE incident_id = ? AND origin = 'AUTOMATIC')",
            Boolean.class, incidentId));
    }

    public boolean hasActive(UUID incidentId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM ai_analyses WHERE incident_id = ? AND status IN ('PENDING', 'RUNNING'))
            """, Boolean.class, incidentId));
    }

    public void insert(UUID id, UUID workspaceId, UUID incidentId, UUID requestedBy, UUID requestId,
                       String origin, String model, String inputSha256, String runContext, Instant now) {
        jdbcTemplate.update("""
            INSERT INTO ai_analyses (id, workspace_id, incident_id, requested_by, request_id, origin,
                provider, model, prompt_version, input_sha256, run_context, created_at)
            VALUES (?, ?, ?, ?, ?, ?, 'OPENAI', ?, ?, ?, ?, ?)
            """, id, workspaceId, incidentId, requestedBy, requestId, origin, model,
            AnalysisPrompt.VERSION, inputSha256, runContext, Timestamp.from(now));
    }

    public String runContext(UUID incidentId) {
        return jdbcTemplate.query("""
            SELECT r.full_name, w.workflow_name, w.head_branch, w.head_sha, w.run_attempt,
                j.name AS job_name, j.conclusion AS job_conclusion
            FROM incidents i JOIN repositories r ON r.id = i.repository_id AND r.workspace_id = i.workspace_id
            JOIN workflow_runs w ON w.id = i.workflow_run_id AND w.repository_id = r.id
            LEFT JOIN workflow_jobs j ON j.id = i.failed_job_id AND j.workflow_run_id = w.id
            WHERE i.id = ?
            """, (row, number) -> objectMapper.writeValueAsString(new RunContext(row.getString("full_name"),
            row.getString("workflow_name"), row.getString("head_branch"), row.getString("head_sha"),
            row.getInt("run_attempt"), row.getString("job_name"), row.getString("job_conclusion"))), incidentId)
            .stream().findFirst().orElseThrow(() -> new AnalysisFailure("LOGS_UNAVAILABLE", false));
    }

    private record RunContext(String repository, String workflow, String branch, String commit,
                              int attempt, String failedJob, String conclusion) {
    }

    public List<String> logChunks(UUID incidentId) {
        return jdbcTemplate.queryForList("""
            SELECT content FROM incident_log_snippets WHERE incident_id = ? ORDER BY chunk_index LIMIT 10
            """, String.class, incidentId);
    }

    public long callsSince(UUID workspaceId, Instant since) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_usage_events WHERE workspace_id = ? AND created_at >= ?",
            Long.class, workspaceId, Timestamp.from(since));
    }

    public void start(UUID id, int attempt, AiProperties properties, Instant now) {
        jdbcTemplate.update("""
            UPDATE ai_analyses SET status = 'RUNNING', started_at = coalesce(started_at, ?), error_code = NULL WHERE id = ?
            """, Timestamp.from(now), id);
        jdbcTemplate.update("""
            INSERT INTO ai_usage_events (id, workspace_id, analysis_id, attempt, provider, model,
                input_price_per_million, output_price_per_million, created_at)
            SELECT ?, workspace_id, id, ?, provider, model, ?, ?, ? FROM ai_analyses WHERE id = ?
            """, UUID.randomUUID(), attempt, properties.inputPricePerMillion(), properties.outputPricePerMillion(),
            Timestamp.from(now), id);
    }

    public void succeed(UUID id, AnalysisDtos.Report report, Instant now) {
        jdbcTemplate.update("""
            UPDATE ai_analyses SET status = 'SUCCEEDED', summary = ?, suspected_root_cause = ?,
                suggested_fix = ?, evidence = CAST(? AS jsonb), assessment_status = ?,
                missing_information = CAST(? AS jsonb), verification_steps = CAST(? AS jsonb),
                error_code = NULL, completed_at = ? WHERE id = ?
            """, report.summary(), report.suspectedRootCause(), report.suggestedFix(),
            objectMapper.writeValueAsString(report.evidence()), report.assessmentStatus(),
            objectMapper.writeValueAsString(report.missingInformation()), objectMapper.writeValueAsString(report.verificationSteps()),
            Timestamp.from(now), id);
    }

    public void fail(UUID id, String code, boolean exhausted, Instant now) {
        jdbcTemplate.update("""
            UPDATE ai_analyses SET status = ?, error_code = ?, completed_at = ? WHERE id = ?
            """, exhausted ? "FAILED" : "PENDING", code, exhausted ? Timestamp.from(now) : null, id);
    }

    public void finishUsage(UUID id, int attempt, Optional<AnalysisDtos.ProviderResult> result, String status,
                            String errorCode, long durationMs) {
        Long inputTokens = result.map(AnalysisDtos.ProviderResult::inputTokens).orElse(null);
        Long outputTokens = result.map(AnalysisDtos.ProviderResult::outputTokens).orElse(null);
        jdbcTemplate.update("""
            UPDATE ai_usage_events SET status = ?, input_tokens = ?, output_tokens = ?, duration_ms = ?, error_code = ?,
                estimated_cost_usd = CASE WHEN CAST(? AS bigint) IS NOT NULL AND CAST(? AS bigint) IS NOT NULL
                    AND input_price_per_million IS NOT NULL AND output_price_per_million IS NOT NULL
                    THEN round((CAST(? AS numeric) * input_price_per_million
                        + CAST(? AS numeric) * output_price_per_million) / 1000000, 8) ELSE NULL END
            WHERE analysis_id = ? AND attempt = ? AND operation = 'CHAT'
            """, status, inputTokens, outputTokens, Math.max(0, durationMs), errorCode,
            inputTokens, outputTokens, inputTokens, outputTokens, id, attempt);
    }

    public List<AnalysisDtos.UsageResponse> usage(UUID analysisId) {
        return jdbcTemplate.query("""
            SELECT * FROM ai_usage_events WHERE analysis_id = ? ORDER BY attempt, created_at, id
            """, (row, number) -> new AnalysisDtos.UsageResponse(row.getObject("id", UUID.class), analysisId,
            row.getInt("attempt"), row.getString("operation"), row.getString("provider"), row.getString("model"),
            row.getString("status"), row.getObject("input_tokens", Long.class),
            row.getObject("output_tokens", Long.class), row.getBigDecimal("estimated_cost_usd"),
            row.getObject("duration_ms", Long.class), row.getString("error_code"),
            row.getTimestamp("created_at").toInstant()), analysisId);
    }

    public List<UUID> unfinished() {
        return jdbcTemplate.queryForList("""
            SELECT a.id FROM ai_analyses a JOIN outbox_events o ON o.payload->>'analysisId' = a.id::text
            WHERE o.event_type = 'AI_ANALYSIS' AND o.processing_status = 'FAILED'
                AND a.status IN ('PENDING', 'RUNNING') ORDER BY a.created_at LIMIT 20
            """, UUID.class);
    }

    private AnalysisDtos.AnalysisResponse response(ResultSet row) throws SQLException {
        var evidence = objectMapper.readTree(row.getString("evidence"));
        var quotes = new ArrayList<String>();
        for (var quote : evidence) {
            quotes.add(quote.asString());
        }
        return new AnalysisDtos.AnalysisResponse(row.getObject("id", UUID.class),
            row.getObject("incident_id", UUID.class), row.getString("provider"), row.getString("model"),
            row.getString("prompt_version"), row.getString("status"), row.getString("summary"),
            row.getString("suspected_root_cause"), row.getString("suggested_fix"), List.copyOf(quotes),
            row.getString("error_code"), instant(row, "started_at"), instant(row, "completed_at"),
            row.getTimestamp("created_at").toInstant(), row.getString("run_context"), row.getString("assessment_status"),
            strings(row.getString("missing_information")), strings(row.getString("verification_steps")));
    }

    private List<String> strings(String json) {
        var result = new ArrayList<String>();
        for (var item : objectMapper.readTree(json)) {
            result.add(item.asString());
        }
        return List.copyOf(result);
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}

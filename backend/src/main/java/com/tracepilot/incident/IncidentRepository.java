package com.tracepilot.incident;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IncidentRepository {
    private final JdbcTemplate jdbcTemplate;

    public IncidentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public IncidentDtos.IncidentPage list(UUID workspaceId, int page, int size) {
        var items = jdbcTemplate.query("""
            SELECT * FROM incidents WHERE workspace_id = ?
            ORDER BY detected_at DESC, id LIMIT ? OFFSET ?
            """, (row, number) -> response(row), workspaceId, size, (long) page * size);
        long total = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM incidents WHERE workspace_id = ?", Long.class, workspaceId);
        return new IncidentDtos.IncidentPage(items, page, size, total);
    }

    public Optional<IncidentDtos.IncidentResponse> find(UUID workspaceId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM incidents WHERE workspace_id = ? AND id = ?",
            (row, number) -> response(row), workspaceId, id).stream().findFirst();
    }

    public boolean existsForRun(UUID runId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM incidents WHERE workflow_run_id = ?)", Boolean.class, runId));
    }

    public void insert(UUID id, UUID workspaceId, UUID repositoryId, UUID runId, Optional<UUID> failedJobId,
                       String title, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO incidents (id, workspace_id, repository_id, workflow_run_id, failed_job_id,
                    title, detected_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, workspaceId, repositoryId, runId, failedJobId.orElse(null), title,
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    public boolean changeStatus(UUID id, long version, IncidentStatus status, Instant now) {
        return jdbcTemplate.update("""
                UPDATE incidents SET status = ?, resolved_at = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND version = ?
                """, status.name(), status == IncidentStatus.RESOLVED ? Timestamp.from(now) : null,
            Timestamp.from(now), id, version) == 1;
    }

    public boolean assign(UUID id, long version, UUID assignedTo, Instant now) {
        return jdbcTemplate.update("""
            UPDATE incidents SET assigned_to = ?, updated_at = ?, version = version + 1 WHERE id = ? AND version = ?
            """, assignedTo, Timestamp.from(now), id, version) == 1;
    }

    public Optional<IncidentDtos.LogSource> logSource(UUID incidentId) {
        return jdbcTemplate.query("""
            SELECT i.id, i.workspace_id, i.repository_id, i.workflow_run_id, i.failed_job_id, j.github_job_id
            FROM incidents i JOIN workflow_jobs j ON j.id = i.failed_job_id WHERE i.id = ?
            """, (row, number) -> new IncidentDtos.LogSource(row.getObject("id", UUID.class),
            row.getObject("workspace_id", UUID.class), row.getObject("repository_id", UUID.class),
            row.getObject("workflow_run_id", UUID.class), row.getObject("failed_job_id", UUID.class),
            row.getLong("github_job_id")), incidentId).stream().findFirst();
    }

    public IncidentDtos.LogsResponse logs(UUID incidentId) {
        var snippets = jdbcTemplate.query("""
            SELECT * FROM incident_log_snippets WHERE incident_id = ? ORDER BY chunk_index
            """, (row, number) -> new IncidentDtos.LogSnippet(row.getInt("chunk_index"), row.getString("content"),
            row.getString("content_sha256"), row.getTimestamp("captured_at").toInstant()), incidentId);
        if (!snippets.isEmpty()) {
            return new IncidentDtos.LogsResponse("READY", null, snippets);
        }
        return jdbcTemplate.query("""
                SELECT processing_status, last_error_code FROM outbox_events
                WHERE event_type = 'INCIDENT_LOG_CAPTURE' AND payload->>'incidentId' = ?
                """, (row, number) -> new IncidentDtos.LogsResponse(row.getString("processing_status"),
                row.getString("last_error_code"), snippets), incidentId.toString()).stream().findFirst()
            .orElseGet(() -> new IncidentDtos.LogsResponse("UNAVAILABLE", null, snippets));
    }

    public void saveSnippet(UUID incidentId, UUID jobId, int index, String content, String sha256, Instant now) {
        jdbcTemplate.update("""
            INSERT INTO incident_log_snippets (id, incident_id, workflow_job_id, chunk_index, content,
                content_sha256, captured_at) VALUES (?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), incidentId, jobId, index, content, sha256, Timestamp.from(now));
    }

    private IncidentDtos.IncidentResponse response(ResultSet row) throws SQLException {
        var resolvedAt = row.getTimestamp("resolved_at");
        return new IncidentDtos.IncidentResponse(row.getObject("id", UUID.class),
            row.getObject("workspace_id", UUID.class), row.getObject("repository_id", UUID.class),
            row.getObject("workflow_run_id", UUID.class), row.getObject("failed_job_id", UUID.class),
            row.getObject("assigned_to", UUID.class), row.getString("title"),
            IncidentStatus.valueOf(row.getString("status")), row.getString("severity"), row.getLong("version"),
            row.getTimestamp("detected_at").toInstant(), resolvedAt == null ? null : resolvedAt.toInstant(),
            row.getTimestamp("updated_at").toInstant());
    }
}

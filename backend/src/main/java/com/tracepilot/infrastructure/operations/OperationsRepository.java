package com.tracepilot.infrastructure.operations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationsRepository {
    private final JdbcTemplate jdbc;

    public OperationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OperationsDtos.Backlog> backlog(UUID workspaceId, Instant now) {
        return jdbc.query("""
            SELECT e.event_type, e.processing_status, count(*) AS count,
                greatest(0, extract(epoch FROM CAST(? AS timestamptz) - min(e.created_at)))::bigint AS oldest_age
            FROM outbox_events e JOIN repositories r ON r.id = e.aggregate_id
            WHERE e.aggregate_type = 'REPOSITORY' AND r.workspace_id = ?
                AND e.processing_status IN ('PENDING', 'RUNNING', 'RETRY', 'FAILED')
            GROUP BY e.event_type, e.processing_status ORDER BY e.event_type, e.processing_status
            """, (row, index) -> new OperationsDtos.Backlog(row.getString("event_type"),
            row.getString("processing_status"), row.getLong("count"), row.getLong("oldest_age")),
            Timestamp.from(now), workspaceId);
    }

    public OperationsDtos.EventPage list(UUID workspaceId, int page, int size) {
        var items = jdbc.query("""
            SELECT e.id, e.aggregate_id, e.event_type, e.status, e.processing_status, e.processing_attempts,
                e.total_processing_attempts, e.replay_count, e.last_error_code, e.created_at, e.available_at, e.processed_at
            FROM outbox_events e JOIN repositories r ON r.id = e.aggregate_id
            WHERE e.aggregate_type = 'REPOSITORY' AND r.workspace_id = ?
            ORDER BY e.created_at DESC, e.id LIMIT ? OFFSET ?
            """, this::map, workspaceId, size, (long) page * size);
        var total = jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events e JOIN repositories r ON r.id = e.aggregate_id
            WHERE e.aggregate_type = 'REPOSITORY' AND r.workspace_id = ?
            """, Long.class, workspaceId);
        return new OperationsDtos.EventPage(items, page, size, total);
    }

    public Optional<OperationsDtos.Event> find(UUID workspaceId, UUID eventId) {
        return jdbc.query("""
            SELECT e.id, e.aggregate_id, e.event_type, e.status, e.processing_status, e.processing_attempts,
                e.total_processing_attempts, e.replay_count, e.last_error_code, e.created_at, e.available_at, e.processed_at
            FROM outbox_events e JOIN repositories r ON r.id = e.aggregate_id
            WHERE e.aggregate_type = 'REPOSITORY' AND e.id = ? AND r.workspace_id = ?
            """, this::map, eventId, workspaceId).stream().findFirst();
    }

    public boolean retry(UUID workspaceId, UUID eventId, int replayCount, Instant now) {
        return jdbc.update("""
            UPDATE outbox_events e SET processing_status = 'PENDING', status = 'PENDING', available_at = ?,
                processed_at = NULL, last_error_code = NULL, processing_attempts = 0, replay_count = replay_count + 1,
                processing_token = NULL, processing_lease_until = NULL, publish_token = NULL, publish_lease_until = NULL
            FROM repositories r WHERE r.id = e.aggregate_id AND e.aggregate_type = 'REPOSITORY'
                AND r.workspace_id = ? AND e.id = ? AND e.replay_count = ?
                AND e.processing_status = 'FAILED' AND e.status = 'PUBLISHED'
                AND e.event_type IN ('WORKFLOW_SYNC', 'INCIDENT_LOG_CAPTURE')
            """, Timestamp.from(now), workspaceId, eventId, replayCount) == 1;
    }

    private OperationsDtos.Event map(ResultSet row, int index) throws SQLException {
        var processed = row.getTimestamp("processed_at");
        return new OperationsDtos.Event(row.getObject("id", UUID.class), row.getObject("aggregate_id", UUID.class),
            row.getString("event_type"), row.getString("status"), row.getString("processing_status"),
            row.getInt("processing_attempts"), row.getInt("total_processing_attempts"), row.getInt("replay_count"),
            row.getString("last_error_code"), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("available_at").toInstant(), processed == null ? null : processed.toInstant());
    }
}

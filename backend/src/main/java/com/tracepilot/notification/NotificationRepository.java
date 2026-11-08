package com.tracepilot.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyMembers(UUID workspaceId, UUID incidentId, String type, Instant now) {
        // Callers hold the workspace lock: notification sequence follows commit order within that workspace.
        jdbcTemplate.update("""
            INSERT INTO notifications (id, workspace_id, user_id, incident_id, type, title, created_at)
            SELECT gen_random_uuid(), workspace_id, user_id, ?, ?, 'Incident updated', ?
            FROM workspace_members WHERE workspace_id = ? AND status = 'ACTIVE'
            """, incidentId, type, Timestamp.from(now), workspaceId);
    }

    public NotificationDtos.NotificationPage list(UUID workspaceId, UUID userId, int page, int size) {
        var items = jdbcTemplate.query("""
            SELECT * FROM notifications WHERE workspace_id = ? AND user_id = ?
            ORDER BY sequence_number DESC LIMIT ? OFFSET ?
            """, (row, number) -> response(row), workspaceId, userId, size, (long) page * size);
        long total = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE workspace_id = ? AND user_id = ?",
            Long.class, workspaceId, userId);
        return new NotificationDtos.NotificationPage(items, page, size, total);
    }

    public List<NotificationDtos.Delivery> after(UUID workspaceId, UUID userId, long cursor) {
        return jdbcTemplate.query("""
                SELECT * FROM notifications WHERE workspace_id = ? AND user_id = ? AND sequence_number > ?
                ORDER BY sequence_number LIMIT 50
                """, (row, number) -> new NotificationDtos.Delivery(row.getLong("sequence_number"), response(row)),
            workspaceId, userId, cursor);
    }

    public Optional<Long> cursor(UUID workspaceId, UUID userId, UUID notificationId) {
        return jdbcTemplate.query("""
            SELECT sequence_number FROM notifications WHERE workspace_id = ? AND user_id = ? AND id = ?
            """, (row, number) -> row.getLong(1), workspaceId, userId, notificationId).stream().findFirst();
    }

    public boolean markRead(UUID workspaceId, UUID userId, UUID notificationId, Instant now) {
        return jdbcTemplate.update("""
            UPDATE notifications SET read_at = coalesce(read_at, ?)
            WHERE workspace_id = ? AND user_id = ? AND id = ?
            """, Timestamp.from(now), workspaceId, userId, notificationId) == 1;
    }

    private NotificationDtos.NotificationResponse response(ResultSet row) throws SQLException {
        var readAt = row.getTimestamp("read_at");
        return new NotificationDtos.NotificationResponse(row.getObject("id", UUID.class),
            row.getObject("incident_id", UUID.class), row.getString("type"), row.getString("title"),
            readAt == null ? null : readAt.toInstant(), row.getTimestamp("created_at").toInstant());
    }
}

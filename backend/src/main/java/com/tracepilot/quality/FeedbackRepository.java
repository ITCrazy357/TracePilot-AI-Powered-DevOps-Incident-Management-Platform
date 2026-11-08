package com.tracepilot.quality;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {
    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<QualityDtos.Feedback> find(UUID workspaceId, UUID analysisId, UUID userId) {
        return jdbc.query("""
            SELECT id, analysis_id, helpful, reason, comment, version, created_at, updated_at
            FROM incident_feedback WHERE workspace_id = ? AND analysis_id = ? AND user_id = ?
            """, this::map, workspaceId, analysisId, userId).stream().findFirst();
    }

    public void insert(UUID workspaceId, UUID incidentId, UUID analysisId, UUID userId,
                       QualityDtos.FeedbackRequest request, Instant now) {
        jdbc.update("""
            INSERT INTO incident_feedback
                (id, workspace_id, incident_id, analysis_id, user_id, helpful, reason, comment, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), workspaceId, incidentId, analysisId, userId, request.helpful(),
            request.reason().name(), request.comment(), Timestamp.from(now), Timestamp.from(now));
    }

    public void update(UUID workspaceId, UUID analysisId, UUID userId, QualityDtos.FeedbackRequest request, Instant now) {
        jdbc.update("""
            UPDATE incident_feedback SET helpful = ?, reason = ?, comment = ?, version = version + 1, updated_at = ?
            WHERE workspace_id = ? AND analysis_id = ? AND user_id = ? AND version = ?
            """, request.helpful(), request.reason().name(), request.comment(), Timestamp.from(now),
            workspaceId, analysisId, userId, request.version());
    }

    private QualityDtos.Feedback map(ResultSet row, int index) throws SQLException {
        return new QualityDtos.Feedback(row.getObject("id", UUID.class), row.getObject("analysis_id", UUID.class),
            row.getBoolean("helpful"), FeedbackReason.valueOf(row.getString("reason")), row.getString("comment"),
            row.getLong("version"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}

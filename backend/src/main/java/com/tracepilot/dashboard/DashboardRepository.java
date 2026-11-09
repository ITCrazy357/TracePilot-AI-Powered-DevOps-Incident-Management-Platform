package com.tracepilot.dashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<LocalDate, Long> incidents(UUID workspaceId, Instant from, Instant to) {
        var rows = jdbc.query("""
            SELECT (detected_at AT TIME ZONE 'UTC')::date AS day, count(*) AS count FROM incidents
            WHERE workspace_id = ? AND detected_at >= ? AND detected_at < ? GROUP BY day
            """, (row, index) -> Map.entry(row.getObject("day", LocalDate.class), row.getLong("count")),
            workspaceId, Timestamp.from(from), Timestamp.from(to));
        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<LocalDate, DashboardDtos.Day> runs(UUID workspaceId, Instant from, Instant to) {
        var rows = jdbc.query("""
            SELECT (w.created_at AT TIME ZONE 'UTC')::date AS day,
                count(*) FILTER (WHERE w.conclusion = 'success') AS successful,
                count(*) FILTER (WHERE w.conclusion IN ('failure', 'timed_out', 'startup_failure')) AS failed
            FROM workflow_runs w JOIN repositories r ON r.id = w.repository_id
            WHERE r.workspace_id = ? AND w.created_at >= ? AND w.created_at < ? GROUP BY day
            """, (row, index) -> new DashboardDtos.Day(row.getObject("day", LocalDate.class), 0,
            row.getLong("successful"), row.getLong("failed")), workspaceId, Timestamp.from(from), Timestamp.from(to));
        return rows.stream().collect(Collectors.toMap(DashboardDtos.Day::date, Function.identity()));
    }

    public List<DashboardDtos.Status> statuses(UUID workspaceId, Instant from, Instant to) {
        return jdbc.query("""
            SELECT status, count(*) AS count FROM incidents
            WHERE workspace_id = ? AND detected_at >= ? AND detected_at < ? GROUP BY status ORDER BY status
            """, (row, index) -> new DashboardDtos.Status(row.getString("status"), row.getLong("count")),
            workspaceId, Timestamp.from(from), Timestamp.from(to));
    }
}

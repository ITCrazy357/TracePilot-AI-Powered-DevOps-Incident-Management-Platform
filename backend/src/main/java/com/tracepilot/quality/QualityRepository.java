package com.tracepilot.quality;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QualityRepository {
    private final JdbcTemplate jdbc;

    public QualityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public QualityDtos.AnalysisStats analyses(UUID workspaceId, Instant from, Instant to) {
        return jdbc.queryForObject("""
            SELECT count(*) AS total,
                count(*) FILTER (WHERE status = 'PENDING') AS pending,
                count(*) FILTER (WHERE status = 'RUNNING') AS running,
                count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                count(*) FILTER (WHERE status = 'FAILED') AS failed,
                count(*) FILTER (WHERE status = 'SUCCEEDED')::double precision
                    / NULLIF(count(*) FILTER (WHERE status IN ('SUCCEEDED', 'FAILED')), 0) AS success_rate,
                avg(extract(epoch FROM completed_at - created_at) * 1000)
                    FILTER (WHERE status IN ('SUCCEEDED', 'FAILED')) AS average_latency_ms,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM completed_at - created_at) * 1000)
                    FILTER (WHERE status IN ('SUCCEEDED', 'FAILED')) AS p95_latency_ms
            FROM ai_analyses WHERE workspace_id = ? AND created_at >= ? AND created_at < ?
            """, (row, index) -> new QualityDtos.AnalysisStats(row.getLong("total"), row.getLong("pending"),
            row.getLong("running"), row.getLong("succeeded"), row.getLong("failed"),
            nullableDouble(row, "success_rate"), nullableDouble(row, "average_latency_ms"),
            nullableDouble(row, "p95_latency_ms")), workspaceId, Timestamp.from(from), Timestamp.from(to));
    }

    public QualityDtos.FeedbackStats feedback(UUID workspaceId, Instant from, Instant to, long succeeded) {
        // Aggregate separately from usage: multiple votes and retries must never multiply one another.
        return jdbc.queryForObject("""
            SELECT count(*) AS votes, count(*) FILTER (WHERE f.helpful) AS helpful,
                count(*) FILTER (WHERE NOT f.helpful) AS unhelpful, count(DISTINCT f.analysis_id) AS reviewed,
                count(*) FILTER (WHERE f.helpful)::double precision / NULLIF(count(*), 0) AS helpful_rate
            FROM incident_feedback f JOIN ai_analyses a ON a.id = f.analysis_id AND a.workspace_id = f.workspace_id
            WHERE a.workspace_id = ? AND a.created_at >= ? AND a.created_at < ? AND a.status = 'SUCCEEDED'
            """, (row, index) -> new QualityDtos.FeedbackStats(row.getLong("votes"), row.getLong("helpful"),
            row.getLong("unhelpful"), row.getLong("reviewed"), nullableDouble(row, "helpful_rate"),
            succeeded == 0 ? null : (double) row.getLong("reviewed") / succeeded),
            workspaceId, Timestamp.from(from), Timestamp.from(to));
    }

    public List<QualityDtos.ReasonCount> reasons(UUID workspaceId, Instant from, Instant to) {
        return jdbc.query("""
            SELECT f.reason, f.helpful, count(*) AS count
            FROM incident_feedback f JOIN ai_analyses a ON a.id = f.analysis_id AND a.workspace_id = f.workspace_id
            WHERE a.workspace_id = ? AND a.created_at >= ? AND a.created_at < ? AND a.status = 'SUCCEEDED'
            GROUP BY f.reason, f.helpful ORDER BY count DESC, f.reason, f.helpful
            """, (row, index) -> new QualityDtos.ReasonCount(FeedbackReason.valueOf(row.getString("reason")),
            row.getBoolean("helpful"), row.getLong("count")), workspaceId, Timestamp.from(from), Timestamp.from(to));
    }

    public List<QualityDtos.UsageStats> usage(UUID workspaceId, Instant from, Instant to) {
        // Include independent knowledge/search embeddings, whose analysis_id is null.
        return jdbc.query("""
            SELECT operation, provider, model, count(*) AS calls,
                count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                count(*) FILTER (WHERE status = 'FAILED') AS failed,
                count(*) FILTER (WHERE status = 'UNKNOWN') AS unknown,
                sum(input_tokens)::bigint AS input_tokens, sum(output_tokens)::bigint AS output_tokens,
                count(input_tokens) AS input_samples, count(output_tokens) AS output_samples,
                sum(estimated_cost_usd) AS known_cost, count(estimated_cost_usd) AS priced_calls,
                count(duration_ms) AS duration_samples, avg(duration_ms) AS average_ms,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_ms
            FROM ai_usage_events WHERE workspace_id = ? AND created_at >= ? AND created_at < ?
            GROUP BY operation, provider, model ORDER BY operation, provider, model
            """, (row, index) -> new QualityDtos.UsageStats(row.getString("operation"), row.getString("provider"),
            row.getString("model"), row.getLong("calls"), row.getLong("succeeded"), row.getLong("failed"),
            row.getLong("unknown"), row.getObject("input_tokens", Long.class), row.getObject("output_tokens", Long.class),
            row.getLong("input_samples"), row.getLong("output_samples"), row.getBigDecimal("known_cost"),
            row.getLong("priced_calls"), row.getLong("duration_samples"),
            nullableDouble(row, "average_ms"), nullableDouble(row, "p95_ms")),
            workspaceId, Timestamp.from(from), Timestamp.from(to));
    }

    public List<QualityDtos.ErrorCount> errors(UUID workspaceId, Instant from, Instant to) {
        return jdbc.query("""
            SELECT coalesce(error_code, 'UNSPECIFIED') AS code, count(*) AS count
            FROM ai_analyses WHERE workspace_id = ? AND created_at >= ? AND created_at < ? AND status = 'FAILED'
            GROUP BY error_code ORDER BY count DESC, code
            """, (row, index) -> new QualityDtos.ErrorCount(row.getString("code"), row.getLong("count")),
            workspaceId, Timestamp.from(from), Timestamp.from(to));
    }

    private Double nullableDouble(ResultSet row, String column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }
}

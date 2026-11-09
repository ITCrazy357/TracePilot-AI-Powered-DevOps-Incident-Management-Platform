package com.tracepilot.quality;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UsageRepository {
    private static final String AGGREGATES = """
        count(*) AS calls,
        count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
        count(*) FILTER (WHERE status = 'FAILED') AS failed,
        count(*) FILTER (WHERE status = 'UNKNOWN') AS unknown,
        sum(input_tokens)::bigint AS input_tokens, sum(output_tokens)::bigint AS output_tokens,
        count(input_tokens) AS input_samples, count(output_tokens) AS output_samples,
        sum(estimated_cost_usd) AS known_cost, count(estimated_cost_usd) AS priced_calls
        """;
    private final NamedParameterJdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public record Data(UsageDtos.Totals totals, Map<LocalDate, UsageDtos.Day> daily,
                       List<UsageDtos.Model> byModel, List<String> models) {
    }

    public Data read(UUID workspaceId, Instant from, Instant to, UsageDtos.Filters filters) {
        var parameters = new MapSqlParameterSource().addValue("workspace", workspaceId)
            .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        String range = " FROM ai_usage_events WHERE workspace_id = :workspace AND created_at >= :from AND created_at < :to";
        var where = new StringBuilder(range);
        if (filters.model() != null) {
            where.append(" AND model = :model");
            parameters.addValue("model", filters.model());
        }
        if (filters.operation() != null) {
            where.append(" AND operation = :operation");
            parameters.addValue("operation", filters.operation());
        }
        var totals = jdbc.queryForObject("SELECT " + AGGREGATES + where, parameters, (row, index) -> totals(row));
        String dailySql = "SELECT (created_at AT TIME ZONE 'UTC')::date AS day, " + AGGREGATES
            + where + " GROUP BY day ORDER BY day";
        var daily = jdbc.query(dailySql,
            parameters, (row, index) -> new UsageDtos.Day(row.getObject("day", LocalDate.class), totals(row)));
        String modelSql = "SELECT operation, provider, model, " + AGGREGATES + where
            + " GROUP BY operation, provider, model ORDER BY operation, provider, model";
        var byModel = jdbc.query(modelSql,
            parameters, (row, index) -> new UsageDtos.Model(row.getString("operation"), row.getString("provider"),
            row.getString("model"), totals(row)));
        var models = jdbc.query("SELECT DISTINCT model" + range + " ORDER BY model",
            parameters, (row, index) -> row.getString("model"));
        var dailyByDate = daily.stream().collect(Collectors.toMap(UsageDtos.Day::date, Function.identity()));
        return new Data(totals, dailyByDate, byModel, models);
    }

    private UsageDtos.Totals totals(ResultSet row) throws SQLException {
        if (row.getLong("calls") == 0) {
            return UsageDtos.Totals.empty();
        }
        return new UsageDtos.Totals(row.getLong("calls"), row.getLong("succeeded"), row.getLong("failed"),
            row.getLong("unknown"), row.getObject("input_tokens", Long.class), row.getObject("output_tokens", Long.class),
            row.getLong("input_samples"), row.getLong("output_samples"), row.getBigDecimal("known_cost"), row.getLong("priced_calls"));
    }
}

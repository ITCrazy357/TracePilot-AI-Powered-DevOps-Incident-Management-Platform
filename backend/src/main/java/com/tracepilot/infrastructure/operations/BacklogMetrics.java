package com.tracepilot.infrastructure.operations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BacklogMetrics {
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> values = new HashMap<>();

    public BacklogMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (var event : List.of("WORKFLOW_SYNC", "INCIDENT_LOG_CAPTURE", "AI_ANALYSIS")) {
            for (var state : List.of("PENDING", "RUNNING", "RETRY", "FAILED")) {
                var value = new AtomicLong();
                values.put(event + ":" + state, value);
                registry.gauge("tracepilot.outbox.backlog", Tags.of("event", event, "state", state), value);
            }
        }
    }

    @Scheduled(fixedDelayString = "${tracepilot.operations.sample-delay-ms:10000}")
    public void sample() {
        var snapshot = new HashMap<String, Long>();
        jdbc.query("""
            SELECT event_type, processing_status, count(*) AS count FROM outbox_events
            WHERE processing_status IN ('PENDING', 'RUNNING', 'RETRY', 'FAILED')
            GROUP BY event_type, processing_status
            """, row -> {
            snapshot.put(row.getString("event_type") + ":" + row.getString("processing_status"), row.getLong("count"));
        });
        values.forEach((key, value) -> value.set(snapshot.getOrDefault(key, 0L)));
    }
}

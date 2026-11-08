package com.tracepilot.github;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookDeliveryRepository {
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public WebhookDeliveryRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public boolean begin(String deliveryId, String eventType, String action, String payloadHash) {
        // ON CONFLICT waits for a concurrent delivery to commit, without leaving
        // the transaction aborted by a unique-constraint exception.
        return jdbcTemplate.update("""
                INSERT INTO webhook_deliveries
                    (id, github_delivery_id, event_type, event_action, payload_sha256, status, received_at)
                VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?)
                ON CONFLICT (github_delivery_id) DO NOTHING
                """, UUID.randomUUID(), deliveryId, eventType, action, payloadHash,
            Timestamp.from(clock.instant())) == 1;
    }

    public Delivery find(String deliveryId) {
        return jdbcTemplate.queryForObject("""
            SELECT payload_sha256, event_type, status FROM webhook_deliveries WHERE github_delivery_id = ?
            """, (row, number) -> new Delivery(row.getString("payload_sha256"),
            row.getString("event_type"), row.getString("status")), deliveryId);
    }

    public void complete(String deliveryId, Optional<UUID> installationId, String status) {
        jdbcTemplate.update("""
            UPDATE webhook_deliveries SET installation_id = ?, status = ?, processed_at = ?
            WHERE github_delivery_id = ?
            """, installationId.orElse(null), status, Timestamp.from(clock.instant()), deliveryId);
    }

    public record Delivery(String payloadHash, String eventType, String status) {
    }
}

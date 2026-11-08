package com.tracepilot.pipeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final PipelineProperties properties;

    public OutboxRepository(JdbcTemplate jdbcTemplate, Clock clock, PipelineProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(UUID repositoryId, PipelineDtos.Work work, Optional<String> deliveryId) {
        var id = UUID.randomUUID();
        var now = timestamp(clock.instant());
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload,
                source_delivery_id, available_at, created_at)
            VALUES (?, 'REPOSITORY', ?, 'WORKFLOW_SYNC', jsonb_build_object('runId', ?, 'runAttempt', ?),
                (SELECT id FROM webhook_deliveries WHERE github_delivery_id = ?), ?, ?)
            """, id, repositoryId, work.runId(), work.runAttempt(), deliveryId.orElse(null), now, now);
        return id;
    }

    @Transactional
    public Optional<PublicationClaim> claimPublication() {
        var token = UUID.randomUUID();
        var now = clock.instant();
        return jdbcTemplate.query("""
                UPDATE outbox_events SET status = 'PUBLISHING', publish_token = ?, publish_lease_until = ?,
                    attempts = attempts + 1
                WHERE id = (
                    SELECT id FROM outbox_events
                    WHERE event_type = 'WORKFLOW_SYNC' AND processing_status IN ('PENDING', 'RETRY', 'FAILED')
                        AND available_at <= ?
                        AND (status = 'PENDING' OR (status = 'PUBLISHING' AND publish_lease_until <= ?))
                    ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                RETURNING id, publish_token, attempts, processing_status
                """, (row, number) -> new PublicationClaim(row.getObject("id", UUID.class),
                row.getObject("publish_token", UUID.class), row.getInt("attempts"),
                "FAILED".equals(row.getString("processing_status"))),
            token, timestamp(now.plusSeconds(30)), timestamp(now), timestamp(now)).stream().findFirst();
    }

    public void published(PublicationClaim claim) {
        jdbcTemplate.update("""
            UPDATE outbox_events SET status = 'PUBLISHED', published_at = ?,
                publish_token = NULL, publish_lease_until = NULL
            WHERE id = ? AND status = 'PUBLISHING' AND publish_token = ?
            """, timestamp(clock.instant()), claim.id(), claim.token());
    }

    public void publicationFailed(PublicationClaim claim) {
        long delaySeconds = Math.min(30, 1L << Math.min(claim.attempts(), 5));
        jdbcTemplate.update("""
            UPDATE outbox_events SET status = 'PENDING', available_at = ?,
                publish_token = NULL, publish_lease_until = NULL, last_error_code = 'BROKER_UNAVAILABLE'
            WHERE id = ? AND status = 'PUBLISHING' AND publish_token = ?
            """, timestamp(clock.instant().plusSeconds(delaySeconds)), claim.id(), claim.token());
    }

    @Transactional
    public Optional<ProcessingClaim> claimProcessing(UUID id) {
        var now = clock.instant();
        var token = UUID.randomUUID();
        return jdbcTemplate.query("""
                UPDATE outbox_events SET processing_status = 'RUNNING', processing_token = ?,
                    processing_lease_until = ?, processing_attempts = processing_attempts + 1
                WHERE id = ? AND event_type = 'WORKFLOW_SYNC' AND available_at <= ?
                    AND processing_status IN ('PENDING', 'RETRY') AND processing_attempts < ?
                RETURNING id, aggregate_id, payload->>'runId' AS run_id, payload->>'runAttempt' AS run_attempt,
                    processing_token, processing_attempts
                """, (row, number) -> new ProcessingClaim(row.getObject("id", UUID.class),
                row.getObject("aggregate_id", UUID.class), new PipelineDtos.Work(row.getLong("run_id"),
                row.getInt("run_attempt")), row.getObject("processing_token", UUID.class),
                row.getInt("processing_attempts")), token, timestamp(now.plus(properties.processingLease())),
            id, timestamp(now), properties.maxAttempts()).stream().findFirst();
    }

    public boolean lockProcessingClaim(ProcessingClaim claim) {
        return !jdbcTemplate.queryForList("""
            SELECT id FROM outbox_events WHERE id = ? AND processing_status = 'RUNNING'
                AND processing_token = ? FOR UPDATE
            """, UUID.class, claim.id(), claim.token()).isEmpty();
    }

    public void complete(ProcessingClaim claim, String processingStatus) {
        var now = timestamp(clock.instant());
        jdbcTemplate.update("""
            UPDATE outbox_events SET processing_status = ?, processed_at = ?, last_error_code = NULL,
                status = 'PUBLISHED', published_at = coalesce(published_at, ?),
                processing_token = NULL, processing_lease_until = NULL, publish_token = NULL, publish_lease_until = NULL
            WHERE id = ? AND processing_status = 'RUNNING' AND processing_token = ?
            """, processingStatus, now, now, claim.id(), claim.token());
    }

    public void processingFailed(ProcessingClaim claim, String errorCode) {
        boolean exhausted = claim.attempts() >= properties.maxAttempts();
        var now = clock.instant();
        var delay = properties.retryDelay().multipliedBy(1L << Math.min(claim.attempts() - 1, 5));
        if (delay.compareTo(Duration.ofSeconds(30)) > 0) {
            delay = Duration.ofSeconds(30);
        }
        jdbcTemplate.update("""
                UPDATE outbox_events SET processing_status = ?, processed_at = ?, status = 'PENDING', available_at = ?,
                    last_error_code = ?, processing_token = NULL, processing_lease_until = NULL,
                    publish_token = NULL, publish_lease_until = NULL
                WHERE id = ? AND processing_status = 'RUNNING' AND processing_token = ?
                """, exhausted ? "FAILED" : "RETRY", exhausted ? timestamp(now) : null,
            timestamp(exhausted ? now : now.plus(delay)), errorCode, claim.id(), claim.token());
    }

    @Transactional
    public void recoverExpired() {
        var now = timestamp(clock.instant());
        jdbcTemplate.update("""
            UPDATE outbox_events SET processing_status =
                CASE WHEN processing_attempts >= ? THEN 'FAILED' ELSE 'RETRY' END,
                status = 'PENDING', available_at = ?, last_error_code = 'WORKER_LEASE_EXPIRED',
                processed_at = CASE WHEN processing_attempts >= ? THEN CAST(? AS timestamptz) ELSE NULL END,
                processing_token = NULL, processing_lease_until = NULL, publish_token = NULL, publish_lease_until = NULL
            WHERE event_type = 'WORKFLOW_SYNC' AND processing_status = 'RUNNING' AND processing_lease_until <= ?
            """, properties.maxAttempts(), now, properties.maxAttempts(), now, now);
        jdbcTemplate.update("""
            UPDATE outbox_events SET status = 'PENDING', available_at = ?
            WHERE event_type = 'WORKFLOW_SYNC' AND status = 'PUBLISHED'
                AND processing_status IN ('PENDING', 'RETRY') AND published_at <= ?
            """, now, timestamp(clock.instant().minus(properties.processingLease())));
    }

    public PipelineDtos.SyncPage list(UUID repositoryId, int page, int size) {
        var items = jdbcTemplate.query("""
            SELECT *, payload->>'runId' AS run_id, payload->>'runAttempt' AS run_attempt
            FROM outbox_events WHERE aggregate_type = 'REPOSITORY' AND aggregate_id = ?
                AND event_type = 'WORKFLOW_SYNC' ORDER BY created_at DESC, id LIMIT ? OFFSET ?
            """, (row, number) -> response(row), repositoryId, size, (long) page * size);
        var total = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM outbox_events WHERE aggregate_type = 'REPOSITORY' AND aggregate_id = ?
                AND event_type = 'WORKFLOW_SYNC'
            """, Long.class, repositoryId);
        return new PipelineDtos.SyncPage(items, page, size, total);
    }

    public PipelineDtos.SyncResponse find(UUID id) {
        return jdbcTemplate.queryForObject("""
            SELECT *, payload->>'runId' AS run_id, payload->>'runAttempt' AS run_attempt
            FROM outbox_events WHERE id = ?
            """, (row, number) -> response(row), id);
    }

    private PipelineDtos.SyncResponse response(ResultSet row) throws SQLException {
        var processedAt = row.getTimestamp("processed_at");
        return new PipelineDtos.SyncResponse(row.getObject("id", UUID.class), row.getObject("aggregate_id", UUID.class),
            row.getLong("run_id"), row.getInt("run_attempt"), row.getString("status"),
            row.getString("processing_status"), row.getInt("processing_attempts"), row.getString("last_error_code"),
            row.getTimestamp("created_at").toInstant(), processedAt == null ? null : processedAt.toInstant());
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    public record PublicationClaim(UUID id, UUID token, int attempts, boolean deadLetter) {
    }

    public record ProcessingClaim(UUID id, UUID repositoryId, PipelineDtos.Work work, UUID token, int attempts) {
    }
}

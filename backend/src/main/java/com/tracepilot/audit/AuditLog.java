package com.tracepilot.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditLog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID workspaceId, UUID actorId, AuditAction action, UUID entityId, Map<String, ?> metadata) {
        // Accept identifiers, enums and scalar facts only. Free text, credentials and payloads do not belong in audit.
        for (var value : metadata.values()) {
            if (!(value instanceof UUID || value instanceof Enum<?> || value instanceof Boolean
                || value instanceof Integer || value instanceof Long)) {
                throw new IllegalArgumentException("Unsupported audit metadata value.");
            }
        }
        var requestId = MDC.get("requestId");
        jdbc.update("""
            INSERT INTO audit_logs
                (id, workspace_id, actor_user_id, action, entity_type, entity_id, metadata, request_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            """, UUID.randomUUID(), workspaceId, actorId, action.name(), action.entityType(), entityId,
            mapper.writeValueAsString(metadata), requestId == null ? null : UUID.fromString(requestId),
            Timestamp.from(clock.instant()));
    }
}

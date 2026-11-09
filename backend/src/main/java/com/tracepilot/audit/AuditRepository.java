package com.tracepilot.audit;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AuditRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public AuditDtos.Page list(UUID workspaceId, int page, int size) {
        var items = jdbc.query("""
            SELECT id, actor_user_id, action, entity_type, entity_id, metadata, request_id, created_at
            FROM audit_logs WHERE workspace_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?
            """, (row, index) -> new AuditDtos.Entry(row.getObject("id", UUID.class),
            row.getObject("actor_user_id", UUID.class), row.getString("action"), row.getString("entity_type"),
            row.getObject("entity_id", UUID.class), mapper.readTree(row.getString("metadata")),
            row.getObject("request_id", UUID.class), row.getTimestamp("created_at").toInstant()),
            workspaceId, size, (long) page * size);
        var total = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE workspace_id = ?", Long.class, workspaceId);
        return new AuditDtos.Page(items, page, size, total);
    }
}

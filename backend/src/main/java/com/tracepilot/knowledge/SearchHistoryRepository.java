package com.tracepilot.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SearchHistoryRepository {
    public record Entry(UUID id, String query, KnowledgeDtos.SearchMode mode, int topK, int resultCount, Instant createdAt) {
    }

    public record Detail(Entry search, KnowledgeDtos.SearchResponse result) {
    }

    public record Page(List<Entry> items, int page, int size, long total) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SearchHistoryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(UUID id, UUID workspaceId, UUID userId, String query, int topK,
                       KnowledgeDtos.SearchResponse result, Instant now) {
        jdbc.update("""
            INSERT INTO knowledge_search_history(id, workspace_id, user_id, query, mode, top_k,
                result_count, result, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            """, id, workspaceId, userId, query, result.mode().name(), topK, result.items().size(),
            mapper.writeValueAsString(result), Timestamp.from(now));
    }

    public Page list(UUID workspaceId, UUID userId, int page, int size) {
        var items = jdbc.query("""
            SELECT id, query, mode, top_k, result_count, created_at FROM knowledge_search_history
            WHERE workspace_id = ? AND user_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?
            """, (row, index) -> new Entry(row.getObject("id", UUID.class), row.getString("query"),
            KnowledgeDtos.SearchMode.valueOf(row.getString("mode")), row.getInt("top_k"),
            row.getInt("result_count"), row.getTimestamp("created_at").toInstant()), workspaceId, userId, size, (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) FROM knowledge_search_history WHERE workspace_id = ? AND user_id = ?",
            Long.class, workspaceId, userId);
        return new Page(items, page, size, total);
    }

    public Optional<Detail> find(UUID workspaceId, UUID userId, UUID id) {
        return jdbc.query("""
            SELECT * FROM knowledge_search_history WHERE workspace_id = ? AND user_id = ? AND id = ?
            """, (row, index) -> new Detail(new Entry(id, row.getString("query"),
            KnowledgeDtos.SearchMode.valueOf(row.getString("mode")), row.getInt("top_k"),
            row.getInt("result_count"), row.getTimestamp("created_at").toInstant()),
            mapper.readValue(row.getString("result"), KnowledgeDtos.SearchResponse.class)), workspaceId, userId, id)
            .stream().findFirst();
    }

    public int delete(UUID workspaceId, UUID userId, UUID id) {
        return jdbc.update("DELETE FROM knowledge_search_history WHERE workspace_id = ? AND user_id = ? AND id = ?",
            workspaceId, userId, id);
    }
}

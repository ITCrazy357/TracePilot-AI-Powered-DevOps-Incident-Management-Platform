package com.tracepilot.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KnowledgeRepository {
    private static final String ELIGIBLE = """
        SELECT k.id, k.article_id, k.article_version, k.content, k.content_sha256, k.embedding
        FROM knowledge_chunks k JOIN knowledge_articles a ON a.id = k.article_id
        WHERE k.workspace_id = ? AND a.workspace_id = ? AND a.status = 'APPROVED'
            AND a.index_status = 'READY' AND a.content_version = k.article_version
            AND k.embedding_model = 'text-embedding-3-small'
        """;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeText text;

    public KnowledgeRepository(JdbcTemplate jdbcTemplate, KnowledgeText text) {
        this.jdbcTemplate = jdbcTemplate;
        this.text = text;
    }

    public Optional<KnowledgeDtos.Article> find(UUID workspaceId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM knowledge_articles WHERE workspace_id = ? AND id = ?",
            (row, number) -> article(row), workspaceId, id).stream().findFirst();
    }

    public KnowledgeDtos.ArticlePage list(UUID workspaceId, int page, int size, KnowledgeFilters filters) {
        var where = new StringBuilder(" WHERE workspace_id = ?");
        var parameters = new ArrayList<Object>();
        parameters.add(workspaceId);
        if (filters.q() != null && !filters.q().isBlank()) {
            where.append(" AND position(lower(?) in lower(title || chr(10) || root_cause || chr(10) || resolution)) > 0");
            parameters.add(filters.q().strip());
        }
        if (filters.status() != null) {
            where.append(" AND status = ?");
            parameters.add(filters.status());
        }
        long total = jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_articles" + where, Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((long) page * size);
        var items = jdbcTemplate.query("SELECT * FROM knowledge_articles" + where + " ORDER BY updated_at DESC, id LIMIT ? OFFSET ?",
            (row, number) -> article(row), parameters.toArray());
        return new KnowledgeDtos.ArticlePage(items, page, size, total);
    }

    public void insert(UUID id, UUID workspaceId, UUID actorId, KnowledgeDtos.CreateRequest request, Instant now) {
        jdbcTemplate.update("""
            INSERT INTO knowledge_articles (id, workspace_id, source_incident_id, title, root_cause, resolution,
                created_by, created_at, updated_at, source_analysis_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, workspaceId, request.sourceIncidentId(), request.title(),
            request.rootCause(), request.resolution(), actorId,
            Timestamp.from(now), Timestamp.from(now), request.sourceAnalysisId());
    }

    public void edit(UUID id, KnowledgeDtos.EditRequest request, Instant now) {
        // Old chunks are immutable evidence. The status/revision change excludes them from future retrieval.
        jdbcTemplate.update("""
            UPDATE knowledge_articles SET title = ?, root_cause = ?, resolution = ?, status = 'DRAFT',
                index_status = 'NONE', approved_by = NULL, approved_at = NULL, index_token = NULL,
                index_lease_until = NULL, index_error_code = NULL, version = version + 1,
                content_version = content_version + 1, updated_at = ? WHERE id = ? AND version = ?
            """, request.title(), request.rootCause(), request.resolution(),
            Timestamp.from(now), id, request.version());
    }

    public boolean indexing(UUID id, Instant now) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM knowledge_articles WHERE id = ? AND index_status = 'RUNNING'
                AND index_lease_until > ?)
            """, Boolean.class, id, Timestamp.from(now)));
    }

    public void approve(UUID id, UUID actorId, UUID token, Instant now) {
        jdbcTemplate.update("""
            UPDATE knowledge_articles SET status = 'APPROVED', index_status = 'RUNNING',
                approved_by = ?, approved_at = ?, index_token = ?, index_lease_until = ?,
                index_error_code = NULL, version = version + 1, updated_at = ? WHERE id = ?
            """, actorId, Timestamp.from(now), token, Timestamp.from(now.plusSeconds(120)), Timestamp.from(now), id);
    }

    public boolean ownsPublication(UUID id, UUID token, long contentVersion) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM knowledge_articles WHERE id = ? AND index_token = ?
                AND content_version = ? AND status = 'APPROVED' AND index_status = 'RUNNING')
            """, Boolean.class, id, token, contentVersion));
    }

    public void insertChunks(KnowledgeDtos.Publication publication, List<float[]> vectors, Instant now) {
        var article = publication.article();
        for (int index = 0; index < publication.chunks().size(); index++) {
            var content = publication.chunks().get(index);
            jdbcTemplate.update("""
                INSERT INTO knowledge_chunks (id, workspace_id, article_id, article_version, chunk_index,
                    content, content_sha256, embedding, embedding_model, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), 'text-embedding-3-small', ?)
                """, UUID.randomUUID(), article.workspaceId(), article.id(), article.contentVersion(), index,
                content, text.sha256(content), text.vector(vectors.get(index)), Timestamp.from(now));
        }
        jdbcTemplate.update("""
            UPDATE knowledge_articles SET index_status = 'READY', index_token = NULL, index_lease_until = NULL,
                version = version + 1, updated_at = ? WHERE id = ?
            """, Timestamp.from(now), article.id());
    }

    public void failed(UUID id, UUID token, String code, Instant now) {
        jdbcTemplate.update("""
            UPDATE knowledge_articles SET index_status = 'FAILED', index_error_code = ?, index_token = NULL,
                index_lease_until = NULL, version = version + 1, updated_at = ? WHERE id = ? AND index_token = ?
            """, code, Timestamp.from(now), id, token);
    }

    public void archive(UUID id, Instant now) {
        jdbcTemplate.update("""
            UPDATE knowledge_articles SET status = 'ARCHIVED', index_status = 'NONE', index_token = NULL,
                index_lease_until = NULL, version = version + 1, updated_at = ? WHERE id = ?
            """, Timestamp.from(now), id);
    }

    public boolean hasSearchable(UUID workspaceId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS (" + ELIGIBLE + ")", Boolean.class, workspaceId, workspaceId));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDtos.Chunk> search(UUID workspaceId, float[] vector, String query, int topK,
                                           KnowledgeDtos.SearchMode mode, double minimumSimilarity) {
        var encoded = text.vector(vector);
        if (mode == KnowledgeDtos.SearchMode.APPROXIMATE) {
            jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = 'strict_order'");
            jdbcTemplate.execute("SET LOCAL hnsw.ef_search = 100");
            return jdbcTemplate.query("""
                SELECT k.id, k.article_id, k.article_version, k.content, k.content_sha256,
                    1 - (k.embedding <=> CAST(? AS vector)) AS similarity
                FROM knowledge_chunks k JOIN knowledge_articles a ON a.id = k.article_id
                WHERE k.workspace_id = ? AND a.workspace_id = ? AND a.status = 'APPROVED'
                    AND a.index_status = 'READY' AND a.content_version = k.article_version
                    AND k.embedding_model = 'text-embedding-3-small'
                    AND 1 - (k.embedding <=> CAST(? AS vector)) >= ?
                ORDER BY k.embedding <=> CAST(? AS vector) LIMIT ?
                """, (row, number) -> chunk(row), encoded, workspaceId, workspaceId, encoded,
                minimumSimilarity, encoded, topK);
        }
        if (mode == KnowledgeDtos.SearchMode.HYBRID) {
            return hybrid(workspaceId, encoded, query, topK, minimumSimilarity);
        }
        // Materialization filters the tenant and approval status before the exact distance sort.
        return jdbcTemplate.query("WITH eligible AS MATERIALIZED (" + ELIGIBLE + """
            ) SELECT id, article_id, article_version, content, content_sha256,
                1 - (embedding <=> CAST(? AS vector)) AS similarity FROM eligible
            WHERE 1 - (embedding <=> CAST(? AS vector)) >= ?
            ORDER BY embedding <=> CAST(? AS vector), id LIMIT ?
            """, (row, number) -> chunk(row), workspaceId, workspaceId, encoded, encoded,
            minimumSimilarity, encoded, topK);
    }

    private List<KnowledgeDtos.Chunk> hybrid(UUID workspaceId, String vector, String query, int topK, double minimum) {
        return jdbcTemplate.query("WITH eligible AS MATERIALIZED (" + ELIGIBLE + """
            ), scored AS (
                SELECT *, 1 - (embedding <=> CAST(? AS vector)) AS similarity,
                    ts_rank_cd(to_tsvector('simple', content), websearch_to_tsquery('simple', ?)) AS lexical
                FROM eligible
            ), semantic AS (
                SELECT id, row_number() OVER (ORDER BY similarity DESC, id) AS rank
                FROM scored WHERE similarity >= ? ORDER BY similarity DESC, id LIMIT 20
            ), lexical AS (
                SELECT id, row_number() OVER (ORDER BY lexical DESC, id) AS rank
                FROM scored WHERE lexical > 0 ORDER BY lexical DESC, id LIMIT 20
            ), fused AS (
                SELECT id, sum(score) AS score FROM (
                    SELECT id, 1.0 / (60 + rank) AS score FROM semantic
                    UNION ALL SELECT id, 1.0 / (60 + rank) AS score FROM lexical
                ) ranks GROUP BY id
            ) SELECT scored.id, article_id, article_version, content, content_sha256, similarity
            FROM scored JOIN fused USING (id) ORDER BY fused.score DESC, scored.id LIMIT ?
            """, (row, number) -> chunk(row), workspaceId, workspaceId, vector, query, minimum, topK);
    }

    public boolean stillEligible(UUID workspaceId, List<KnowledgeDtos.Chunk> chunks) {
        if (chunks.isEmpty()) {
            return true;
        }
        var parameters = new ArrayList<Object>();
        parameters.add(workspaceId);
        parameters.add(workspaceId);
        for (var chunk : chunks) {
            parameters.add(chunk.id());
            parameters.add(chunk.articleVersion());
            parameters.add(chunk.contentSha256());
        }
        var tuples = String.join(", ", Collections.nCopies(chunks.size(), "(?, ?, ?)"));
        long eligible = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM knowledge_chunks k JOIN knowledge_articles a ON a.id = k.article_id
            WHERE k.workspace_id = ? AND a.workspace_id = ? AND a.status = 'APPROVED'
                AND a.index_status = 'READY' AND a.content_version = k.article_version
                AND (k.id, k.article_version, k.content_sha256) IN (
            """ + tuples + ")", Long.class, parameters.toArray());
        return eligible == chunks.size();
    }

    private KnowledgeDtos.Chunk chunk(ResultSet row) throws SQLException {
        return new KnowledgeDtos.Chunk(row.getObject("id", UUID.class), row.getObject("article_id", UUID.class),
            row.getLong("article_version"), row.getString("content"), row.getString("content_sha256"),
            Math.clamp(row.getDouble("similarity"), -1, 1));
    }

    private KnowledgeDtos.Article article(ResultSet row) throws SQLException {
        var approvedAt = row.getTimestamp("approved_at");
        return new KnowledgeDtos.Article(row.getObject("id", UUID.class), row.getObject("workspace_id", UUID.class),
            row.getObject("source_incident_id", UUID.class), row.getString("title"), row.getString("root_cause"),
            row.getString("resolution"), row.getString("status"), row.getString("index_status"), row.getLong("version"),
            row.getLong("content_version"), row.getObject("created_by", UUID.class),
            row.getObject("approved_by", UUID.class), approvedAt == null ? null : approvedAt.toInstant(),
            row.getString("index_error_code"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
            row.getObject("source_analysis_id", UUID.class));
    }

    public boolean hasSuccessfulAnalysis(UUID workspaceId, UUID incidentId, UUID analysisId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM ai_analyses
                WHERE workspace_id = ? AND incident_id = ? AND id = ? AND status = 'SUCCEEDED')
            """, Boolean.class, workspaceId, incidentId, analysisId));
    }
}

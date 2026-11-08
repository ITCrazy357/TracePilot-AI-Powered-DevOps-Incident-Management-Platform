package com.tracepilot.knowledge;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.tracepilot.analysis.AiProperties;
import com.tracepilot.analysis.AnalysisFailure;
import com.tracepilot.analysis.AnalysisRepository;
import com.tracepilot.workspace.WorkspaceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingUsageStore {
    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceRepository workspaceRepository;
    private final AnalysisRepository analysisRepository;
    private final AiProperties aiProperties;
    private final RagProperties properties;
    private final Clock clock;

    public EmbeddingUsageStore(JdbcTemplate jdbcTemplate, WorkspaceRepository workspaceRepository,
                               AnalysisRepository analysisRepository, AiProperties aiProperties,
                               RagProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRepository = workspaceRepository;
        this.analysisRepository = analysisRepository;
        this.aiProperties = aiProperties;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public UUID reserve(UUID workspaceId, Optional<UUID> analysisId, Optional<UUID> articleId, int attempt) {
        workspaceRepository.findForUpdate(workspaceId).orElseThrow();
        if (!properties.enabled() || !aiProperties.enabled()) {
            throw new AnalysisFailure("RAG_DISABLED", false);
        }
        if (analysisRepository.callsSince(workspaceId, clock.instant().minus(Duration.ofDays(1))) >= aiProperties.maxCallsPerDay()) {
            throw new AnalysisFailure("AI_DAILY_LIMIT", false);
        }
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO ai_usage_events (id, workspace_id, analysis_id, knowledge_article_id, attempt,
                operation, provider, model, input_price_per_million, created_at)
            VALUES (?, ?, ?, ?, ?, 'EMBEDDING', 'OPENAI', ?, ?, ?)
            """, id, workspaceId, analysisId.orElse(null), articleId.orElse(null), attempt,
            RagProperties.MODEL, properties.embeddingPricePerMillion(), Timestamp.from(clock.instant()));
        return id;
    }

    public void finish(UUID usageId, Long inputTokens, String status, String code, long durationMs) {
        jdbcTemplate.update("""
            UPDATE ai_usage_events SET status = ?, input_tokens = ?, duration_ms = ?, error_code = ?,
                estimated_cost_usd = CASE WHEN CAST(? AS bigint) IS NOT NULL AND input_price_per_million IS NOT NULL
                    THEN round(CAST(? AS numeric) * input_price_per_million / 1000000, 8) ELSE NULL END WHERE id = ?
            """, status, inputTokens, Math.max(0, durationMs), code, inputTokens, inputTokens, usageId);
    }
}

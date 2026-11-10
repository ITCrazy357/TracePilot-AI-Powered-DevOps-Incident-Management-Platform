package com.tracepilot.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class KnowledgeDtos {
    private KnowledgeDtos() {
    }

    public enum SearchMode {
        EXACT, APPROXIMATE, HYBRID
    }

    public record CreateRequest(UUID sourceIncidentId, @NotBlank @Size(max = 160) String title,
                                @NotBlank @Size(max = 3000) String rootCause,
                                @NotBlank @Size(max = 6000) String resolution, UUID sourceAnalysisId,
                                @Size(max = 1000) String symptoms, @Size(max = 1000) String applicability,
                                @Size(max = 1500) String verification) {
    }

    public record EditRequest(@NotNull @Min(0) Long version, @NotBlank @Size(max = 160) String title,
                              @NotBlank @Size(max = 3000) String rootCause,
                              @NotBlank @Size(max = 6000) String resolution,
                              @Size(max = 1000) String symptoms, @Size(max = 1000) String applicability,
                              @Size(max = 1500) String verification) {
    }

    public record ApprovalRequest(@NotNull @Min(0) Long version, Boolean verified) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record SearchRequest(@NotBlank @Size(max = 2000) String query,
                                @Min(1) @Max(5) int topK, @NotNull SearchMode mode) {
    }

    public record Article(UUID id, UUID workspaceId, UUID sourceIncidentId, String title, String rootCause,
                          String resolution, String status, String indexStatus, long version, long contentVersion,
                          UUID createdBy, UUID approvedBy, Instant approvedAt, String indexErrorCode,
                          Instant createdAt, Instant updatedAt, UUID sourceAnalysisId,
                          String symptoms, String applicability, String verification) {
    }

    public record ArticlePage(List<Article> items, int page, int size, long total) {
    }

    public record Chunk(UUID id, UUID articleId, long articleVersion, String content, String contentSha256,
                        double similarity) {
    }

    public record SearchResponse(List<Chunk> items, SearchMode mode, String embeddingModel) {
    }

    public record Publication(Article article, UUID token, List<String> chunks) {
    }

    public record LogSource(UUID id, String content) {
    }

    public record Evidence(UUID id, String sourceType, UUID logSnippetId, Integer logChunkIndex,
                           UUID knowledgeChunkId, UUID articleId, Long articleVersion,
                           String quote, String sourceSha256, Double similarityScore) {
    }
}

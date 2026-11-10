package com.tracepilot.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AnalysisDtos {
    private AnalysisDtos() {
    }

    public record RequestAnalysis(@NotNull UUID requestId, @Size(max = 2000) String additionalContext) {
    }

    public record AnalysisResponse(UUID id, UUID incidentId, String provider, String model, String promptVersion,
                                   String status, String summary, String suspectedRootCause, String suggestedFix,
                                   List<String> evidence, String errorCode, Instant startedAt, Instant completedAt,
                                   Instant createdAt, String runContext, String assessmentStatus,
                                   List<String> missingInformation, List<String> verificationSteps) {
    }

    public record AnalysisPage(List<AnalysisResponse> items, int page, int size, long total) {
    }

    public record UsageResponse(UUID id, UUID analysisId, int attempt, String operation, String provider,
                                String model, String status, Long inputTokens,
                                Long outputTokens, BigDecimal estimatedCostUsd, Long durationMs,
                                String errorCode, Instant createdAt) {
    }

    public record AnalysisWork(AnalysisResponse analysis, UUID workspaceId, UUID repositoryId,
                               UUID requestedBy, String inputSha256, boolean sourceActive) {
    }

    public record ProviderResult(String content, Long inputTokens, Long outputTokens, String finishReason) {
    }

    public record Report(String summary, String suspectedRootCause, String suggestedFix, List<String> evidence,
                         String assessmentStatus, List<String> missingInformation, List<String> verificationSteps) {
    }
}

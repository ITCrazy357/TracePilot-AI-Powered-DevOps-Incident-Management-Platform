package com.tracepilot.quality;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class QualityDtos {
    private QualityDtos() {
    }

    public record FeedbackRequest(@NotNull Boolean helpful, @NotNull FeedbackReason reason,
                                  @Size(max = 2000) String comment, @NotNull @Min(0) Long version) {
    }

    public record Feedback(UUID id, UUID analysisId, boolean helpful, FeedbackReason reason, String comment,
                           long version, Instant createdAt, Instant updatedAt) {
    }

    public record MyFeedback(Feedback feedback) {
    }

    public record Dashboard(Instant from, Instant to, AnalysisStats analyses, FeedbackStats feedback,
                            List<ReasonCount> reasons, List<UsageStats> usage, List<ErrorCount> errors) {
    }

    public record AnalysisStats(long total, long pending, long running, long succeeded, long failed,
                                Double successRate, Double averageLatencyMs, Double p95LatencyMs) {
    }

    public record FeedbackStats(long votes, long helpful, long unhelpful, long reviewedAnalyses,
                                Double helpfulRate, Double reviewCoverage) {
    }

    public record ReasonCount(FeedbackReason reason, boolean helpful, long count) {
    }

    public record UsageStats(String operation, String provider, String model, long calls, long succeeded,
                             long failed, long unknown, Long inputTokens, Long outputTokens,
                             long inputTokenSamples, long outputTokenSamples, BigDecimal knownEstimatedCostUsd,
                             long pricedCalls, long durationSamples, Double averageDurationMs, Double p95DurationMs) {
    }

    public record ErrorCount(String code, long count) {
    }
}

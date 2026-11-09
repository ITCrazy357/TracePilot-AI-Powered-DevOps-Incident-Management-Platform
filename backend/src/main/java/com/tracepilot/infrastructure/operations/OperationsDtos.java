package com.tracepilot.infrastructure.operations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class OperationsDtos {
    private OperationsDtos() {
    }

    public record Backlog(String eventType, String status, long count, Long oldestAgeSeconds) {
    }

    public record Event(UUID id, UUID repositoryId, String eventType, String publicationStatus,
                        String processingStatus, int attempts, int totalAttempts, int replayCount,
                        String errorCode, Instant createdAt, Instant availableAt, Instant processedAt) {
    }

    public record EventPage(List<Event> items, int page, int size, long total) {
    }

    public record RetryRequest(@NotNull @Min(0) @Max(100000) Integer replayCount) {
    }
}

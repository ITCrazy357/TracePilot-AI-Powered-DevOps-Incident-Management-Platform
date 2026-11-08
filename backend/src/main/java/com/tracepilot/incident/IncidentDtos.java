package com.tracepilot.incident;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class IncidentDtos {
    private IncidentDtos() {
    }

    public record ChangeStatusRequest(@NotNull IncidentStatus status, @NotNull @Min(0) Long version) {
    }

    public record AssignRequest(UUID assignedTo, @NotNull @Min(0) Long version) {
    }

    public record IncidentResponse(UUID id, UUID workspaceId, UUID repositoryId, UUID workflowRunId,
                                   UUID failedJobId, UUID assignedTo, String title, IncidentStatus status,
                                   String severity, long version, Instant detectedAt, Instant resolvedAt,
                                   Instant updatedAt) {
    }

    public record IncidentPage(List<IncidentResponse> items, int page, int size, long total) {
    }

    public record LogSnippet(int chunkIndex, String content, String contentSha256, Instant capturedAt) {
    }

    public record LogsResponse(String status, String errorCode, List<LogSnippet> snippets) {
    }

    public record LogSource(UUID incidentId, UUID workspaceId, UUID repositoryId, UUID workflowRunId,
                            UUID jobId, long githubJobId) {
    }
}

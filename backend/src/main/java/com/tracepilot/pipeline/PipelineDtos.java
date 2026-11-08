package com.tracepilot.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public final class PipelineDtos {
    private PipelineDtos() {
    }

    public record SyncRequest(@Min(1) long githubRunId, @Min(1) @Max(1000000) int runAttempt) {
    }

    public record Work(long runId, int runAttempt) {
    }

    public record RepositoryScope(UUID repositoryId, UUID workspaceId, UUID installationId,
                                  long githubRepositoryId, String fullName, long installationVersion) {
    }

    public record RunData(long id, int attempt, long workflowId, String name, String branch, String headSha,
                          String status, String conclusion, String htmlUrl, Instant startedAt,
                          Instant completedAt, Instant sourceUpdatedAt) {
    }

    public record JobData(long id, String name, String status, String conclusion, String htmlUrl,
                          Instant startedAt, Instant completedAt) {
    }

    public record Snapshot(RunData run, List<JobData> jobs) {
    }

    public record RunResponse(UUID id, UUID repositoryId, long githubRunId, int runAttempt, String workflowName,
                              String headBranch, String headSha, String status, String conclusion,
                              String htmlUrl, Instant startedAt, Instant completedAt) {
        static RunResponse from(WorkflowRun run) {
            return new RunResponse(run.getId(), run.getRepositoryId(), run.getGithubRunId(), run.getRunAttempt(),
                run.getWorkflowName(), run.getHeadBranch(), run.getHeadSha(), run.getStatus(), run.getConclusion(),
                run.getHtmlUrl(), run.getStartedAt(), run.getCompletedAt());
        }
    }

    public record JobResponse(UUID id, long githubJobId, String name, String status, String conclusion,
                              String htmlUrl, Instant startedAt, Instant completedAt) {
        static JobResponse from(WorkflowJob job) {
            return new JobResponse(job.getId(), job.getGithubJobId(), job.getName(), job.getStatus(),
                job.getConclusion(), job.getHtmlUrl(), job.getStartedAt(), job.getCompletedAt());
        }
    }

    public record RunPage(List<RunResponse> items, int page, int size, long totalElements) {
    }

    public record SyncResponse(UUID id, UUID repositoryId, long githubRunId, int runAttempt,
                               String publicationStatus, String processingStatus, int attempts,
                               String lastErrorCode, Instant createdAt, Instant processedAt) {
    }

    public record SyncPage(List<SyncResponse> items, int page, int size, long totalElements) {
    }
}

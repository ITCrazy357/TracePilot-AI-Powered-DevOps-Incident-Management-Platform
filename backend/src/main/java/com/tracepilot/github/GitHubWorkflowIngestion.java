package com.tracepilot.github;

import java.util.Optional;

import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineDtos;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class GitHubWorkflowIngestion {
    private final TrackedRepositoryRepository repositoryRepository;
    private final OutboxRepository outboxRepository;

    public GitHubWorkflowIngestion(TrackedRepositoryRepository repositoryRepository,
                                   OutboxRepository outboxRepository) {
        this.repositoryRepository = repositoryRepository;
        this.outboxRepository = outboxRepository;
    }

    public boolean enqueue(GitHubInstallation installation, JsonNode payload, String deliveryId) {
        if (installation.getStatus() != InstallationStatus.ACTIVE) {
            return false;
        }
        long repositoryId = GitHubPayloads.positiveId(payload.path("repository").path("id"));
        var repository = repositoryRepository.findByInstallationIdAndGithubRepositoryId(
            installation.getId(), repositoryId);
        if (repository.isEmpty() || !repository.orElseThrow().isActive()) {
            return false;
        }
        var run = payload.path("workflow_run");
        long runId = GitHubPayloads.positiveId(run.path("id"));
        long attempt = GitHubPayloads.positiveId(run.path("run_attempt"));
        if (attempt > 1000000) {
            throw new IllegalArgumentException("Unsupported workflow attempt.");
        }
        if (run.hasNonNull("repository")
            && GitHubPayloads.positiveId(run.path("repository").path("id")) != repositoryId) {
            throw new IllegalArgumentException("Workflow repository mismatch.");
        }
        outboxRepository.enqueue(repository.orElseThrow().getId(), new PipelineDtos.Work(runId, (int) attempt),
            Optional.of(deliveryId));
        return true;
    }
}

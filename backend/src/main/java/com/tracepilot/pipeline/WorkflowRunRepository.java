package com.tracepilot.pipeline;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {
    Optional<WorkflowRun> findByIdAndRepositoryId(UUID id, UUID repositoryId);

    Optional<WorkflowRun> findByRepositoryIdAndGithubRunIdAndRunAttempt(UUID repositoryId, long runId, int attempt);

    Page<WorkflowRun> findByRepositoryIdOrderByCreatedAtDescIdAsc(UUID repositoryId, Pageable pageable);
}

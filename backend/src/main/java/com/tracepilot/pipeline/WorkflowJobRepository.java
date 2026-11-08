package com.tracepilot.pipeline;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowJobRepository extends JpaRepository<WorkflowJob, UUID> {
    List<WorkflowJob> findByWorkflowRunIdOrderByGithubJobIdAsc(UUID workflowRunId);
}

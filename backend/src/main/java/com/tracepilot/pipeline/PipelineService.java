package com.tracepilot.pipeline;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import com.tracepilot.audit.AuditAction;
import com.tracepilot.audit.AuditLog;

import com.tracepilot.github.GitHubProperties;
import com.tracepilot.workspace.WorkspaceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class PipelineService {
    private final PipelineAccess access;
    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRepository jobRepository;
    private final OutboxRepository outboxRepository;
    private final WorkspaceRepository workspaceRepository;
    private final GitHubProperties gitHubProperties;
    private final AuditLog audit;

    public PipelineService(PipelineAccess access, WorkflowRunRepository runRepository,
                           WorkflowJobRepository jobRepository, OutboxRepository outboxRepository,
                           WorkspaceRepository workspaceRepository, GitHubProperties gitHubProperties, AuditLog audit) {
        this.access = access;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.outboxRepository = outboxRepository;
        this.workspaceRepository = workspaceRepository;
        this.gitHubProperties = gitHubProperties;
        this.audit = audit;
    }

    @Transactional
    public PipelineDtos.SyncResponse requestSync(UUID actorId, UUID workspaceId, UUID repositoryId,
                                                 PipelineDtos.SyncRequest request) {
        workspaceRepository.findForUpdate(workspaceId).orElseThrow(this::notFound);
        access.requireOwner(actorId, workspaceId);
        access.requireRead(actorId, workspaceId, repositoryId);
        gitHubProperties.requireEnabled();
        if (access.currentScope(repositoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Repository is inactive or installation is unavailable.");
        }
        var work = new PipelineDtos.Work(request.githubRunId(), request.runAttempt());
        var id = outboxRepository.enqueue(repositoryId, work, Optional.empty());
        audit.record(workspaceId, actorId, AuditAction.WORKFLOW_SYNC_REQUESTED, id,
            Map.of("repositoryId", repositoryId, "githubRunId", request.githubRunId(),
                "runAttempt", request.runAttempt()));
        return outboxRepository.find(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PipelineDtos.RunPage runs(UUID actorId, UUID workspaceId, UUID repositoryId, int page, int size) {
        access.requireRead(actorId, workspaceId, repositoryId);
        var result = runRepository.findByRepositoryIdOrderByCreatedAtDescIdAsc(
            repositoryId, PageRequest.of(page, size));
        return new PipelineDtos.RunPage(result.getContent().stream().map(PipelineDtos.RunResponse::from).toList(),
            page, size, result.getTotalElements());
    }

    public PipelineDtos.RunResponse run(UUID actorId, UUID workspaceId, UUID repositoryId, UUID runId) {
        access.requireRead(actorId, workspaceId, repositoryId);
        return PipelineDtos.RunResponse.from(findRun(repositoryId, runId));
    }

    public List<PipelineDtos.JobResponse> jobs(UUID actorId, UUID workspaceId, UUID repositoryId, UUID runId) {
        access.requireRead(actorId, workspaceId, repositoryId);
        var run = findRun(repositoryId, runId);
        return jobRepository.findByWorkflowRunIdOrderByGithubJobIdAsc(run.getId()).stream()
            .map(PipelineDtos.JobResponse::from).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PipelineDtos.SyncPage requests(UUID actorId, UUID workspaceId, UUID repositoryId, int page, int size) {
        access.requireRead(actorId, workspaceId, repositoryId);
        return outboxRepository.list(repositoryId, page, size);
    }

    private WorkflowRun findRun(UUID repositoryId, UUID runId) {
        return runRepository.findByIdAndRepositoryId(runId, repositoryId).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow or workspace not found.");
    }
}

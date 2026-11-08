package com.tracepilot.pipeline;

import java.time.Clock;
import java.util.HashMap;

import com.tracepilot.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineSyncStore {
    private final WorkspaceRepository workspaceRepository;
    private final PipelineAccess access;
    private final OutboxRepository outboxRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRepository jobRepository;
    private final Clock clock;

    public PipelineSyncStore(WorkspaceRepository workspaceRepository, PipelineAccess access,
                             OutboxRepository outboxRepository, WorkflowRunRepository runRepository,
                             WorkflowJobRepository jobRepository, Clock clock) {
        this.workspaceRepository = workspaceRepository;
        this.access = access;
        this.outboxRepository = outboxRepository;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.clock = clock;
    }

    @Transactional
    public void apply(OutboxRepository.ProcessingClaim claim, PipelineDtos.RepositoryScope fetchedScope,
                      PipelineDtos.Snapshot snapshot) {
        workspaceRepository.findForUpdate(fetchedScope.workspaceId()).orElseThrow();
        if (!outboxRepository.lockProcessingClaim(claim)) {
            return;
        }
        var currentScope = access.currentScope(claim.repositoryId());
        if (currentScope.isEmpty()) {
            outboxRepository.complete(claim, "IGNORED");
            return;
        }
        if (!currentScope.orElseThrow().equals(fetchedScope)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Repository changed during workflow synchronization.");
        }
        var data = snapshot.run();
        var existing = runRepository.findByRepositoryIdAndGithubRunIdAndRunAttempt(
            claim.repositoryId(), data.id(), data.attempt());
        if (existing.isPresent() && !existing.orElseThrow().accepts(data)) {
            outboxRepository.complete(claim, "SUCCEEDED");
            return;
        }
        var now = clock.instant();
        var run = existing.orElseGet(() -> new WorkflowRun(claim.repositoryId(), data, now));
        run.update(data, now);
        run = runRepository.saveAndFlush(run);
        var jobs = new HashMap<Long, WorkflowJob>();
        for (var job : jobRepository.findByWorkflowRunIdOrderByGithubJobIdAsc(run.getId())) {
            jobs.put(job.getGithubJobId(), job);
        }
        for (var jobData : snapshot.jobs()) {
            var job = jobs.get(jobData.id());
            if (job == null) {
                jobRepository.save(new WorkflowJob(run.getId(), jobData));
            } else {
                job.update(jobData);
            }
        }
        outboxRepository.complete(claim, "SUCCEEDED");
    }
}

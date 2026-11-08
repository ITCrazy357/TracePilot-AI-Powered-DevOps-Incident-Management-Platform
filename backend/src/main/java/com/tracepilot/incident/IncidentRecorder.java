package com.tracepilot.incident;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import com.tracepilot.notification.NotificationRepository;
import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineDtos;
import com.tracepilot.pipeline.WorkflowJob;
import com.tracepilot.pipeline.WorkflowJobRepository;
import com.tracepilot.pipeline.WorkflowRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentRecorder {
    private static final Set<String> FAILURES = Set.of("failure", "timed_out", "startup_failure");
    private final IncidentRepository incidentRepository;
    private final WorkflowJobRepository jobRepository;
    private final OutboxRepository outboxRepository;
    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public IncidentRecorder(IncidentRepository incidentRepository, WorkflowJobRepository jobRepository,
                            OutboxRepository outboxRepository, NotificationRepository notificationRepository,
                            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.jobRepository = jobRepository;
        this.outboxRepository = outboxRepository;
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID workspaceId, WorkflowRun run) {
        if (!"completed".equals(run.getStatus()) || run.getConclusion() == null
            || !FAILURES.contains(run.getConclusion()) || incidentRepository.existsForRun(run.getId())) {
            return;
        }
        var failedJob = jobRepository.findByWorkflowRunIdOrderByGithubJobIdAsc(run.getId()).stream()
            .filter(job -> job.getConclusion() != null && FAILURES.contains(job.getConclusion()))
            .findFirst();
        var incidentId = UUID.randomUUID();
        var title = "Workflow run " + run.getGithubRunId() + ", attempt " + run.getRunAttempt() + " failed";
        incidentRepository.insert(incidentId, workspaceId, run.getRepositoryId(), run.getId(),
            failedJob.map(WorkflowJob::getId), title, clock.instant());
        notificationRepository.notifyMembers(workspaceId, incidentId, "INCIDENT_CREATED", clock.instant());
        if (failedJob.isPresent()) {
            outboxRepository.enqueueLogs(run.getRepositoryId(),
                new PipelineDtos.Work(run.getGithubRunId(), run.getRunAttempt()), incidentId);
        }
    }
}

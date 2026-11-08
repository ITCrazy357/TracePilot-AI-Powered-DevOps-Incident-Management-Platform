package com.tracepilot.analysis;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.tracepilot.incident.IncidentRepository;
import com.tracepilot.notification.NotificationRepository;
import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineProperties;
import com.tracepilot.workspace.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisStore {
    private final WorkspaceRepository workspaceRepository;
    private final AnalysisRepository analysisRepository;
    private final IncidentRepository incidentRepository;
    private final AnalysisAccess access;
    private final AnalysisInput input;
    private final OutboxRepository outboxRepository;
    private final NotificationRepository notificationRepository;
    private final AiProperties properties;
    private final PipelineProperties pipelineProperties;
    private final Clock clock;

    public AnalysisStore(WorkspaceRepository workspaceRepository, AnalysisRepository analysisRepository,
                         IncidentRepository incidentRepository, AnalysisAccess access, AnalysisInput input,
                         OutboxRepository outboxRepository, NotificationRepository notificationRepository,
                         AiProperties properties, PipelineProperties pipelineProperties, Clock clock) {
        this.workspaceRepository = workspaceRepository;
        this.analysisRepository = analysisRepository;
        this.incidentRepository = incidentRepository;
        this.access = access;
        this.input = input;
        this.outboxRepository = outboxRepository;
        this.notificationRepository = notificationRepository;
        this.properties = properties;
        this.pipelineProperties = pipelineProperties;
        this.clock = clock;
    }

    @Transactional
    public Optional<AnalysisInput.PreparedInput> begin(OutboxRepository.ProcessingClaim claim, UUID analysisId) {
        var work = analysisRepository.find(analysisId).orElseThrow();
        workspaceRepository.findForUpdate(work.workspaceId()).orElseThrow();
        if (!outboxRepository.lockProcessingClaim(claim)) {
            return Optional.empty();
        }
        work = analysisRepository.find(analysisId).orElseThrow();
        requireAllowed(work);
        if (!claim.repositoryId().equals(work.repositoryId())) {
            throw new AnalysisFailure("AI_SOURCE_CHANGED", false);
        }
        if (analysisRepository.callsSince(work.workspaceId(), clock.instant().minus(Duration.ofDays(1)))
            >= properties.maxCallsPerDay()) {
            throw new AnalysisFailure("AI_DAILY_LIMIT", false);
        }
        var prepared = input.load(work.analysis().incidentId());
        if (!prepared.sha256().equals(work.inputSha256()) || !work.analysis().model().equals(properties.model())
            || !AnalysisPrompt.VERSION.equals(work.analysis().promptVersion())) {
            throw new AnalysisFailure("AI_SOURCE_CHANGED", false);
        }
        analysisRepository.start(analysisId, claim.attempts(), properties, clock.instant());
        return Optional.of(prepared);
    }

    @Transactional
    public void succeed(OutboxRepository.ProcessingClaim claim, UUID analysisId, AnalysisDtos.ProviderResult result,
                        AnalysisDtos.Report report, long durationMs) {
        var work = analysisRepository.find(analysisId).orElseThrow();
        workspaceRepository.findForUpdate(work.workspaceId()).orElseThrow();
        if (!outboxRepository.lockProcessingClaim(claim)) {
            return;
        }
        work = analysisRepository.find(analysisId).orElseThrow();
        requireAllowed(work);
        analysisRepository.finishUsage(analysisId, claim.attempts(), Optional.of(result), "SUCCEEDED", null, durationMs);
        analysisRepository.succeed(analysisId, report, clock.instant());
        notificationRepository.notifyMembers(work.workspaceId(), work.analysis().incidentId(),
            "AI_ANALYSIS_SUCCEEDED", clock.instant());
        outboxRepository.complete(claim, "SUCCEEDED");
    }

    @Transactional
    public void fail(OutboxRepository.ProcessingClaim claim, UUID analysisId, AnalysisFailure failure,
                     Optional<AnalysisDtos.ProviderResult> result, long durationMs) {
        var work = analysisRepository.find(analysisId).orElseThrow();
        workspaceRepository.findForUpdate(work.workspaceId()).orElseThrow();
        if (!outboxRepository.lockProcessingClaim(claim)) {
            return;
        }
        boolean exhausted = !failure.retryable() || claim.attempts() >= pipelineProperties.maxAttempts();
        analysisRepository.finishUsage(analysisId, claim.attempts(), result,
            result.isPresent() ? "FAILED" : "UNKNOWN", failure.code(), durationMs);
        analysisRepository.fail(analysisId, failure.code(), exhausted, clock.instant());
        if (!failure.retryable()) {
            outboxRepository.failPermanently(claim, failure.code());
        } else {
            outboxRepository.processingFailed(claim, failure.code());
        }
        if (exhausted) {
            notificationRepository.notifyMembers(work.workspaceId(), work.analysis().incidentId(),
                "AI_ANALYSIS_FAILED", clock.instant());
        }
    }

    @Transactional
    public void reconcileFailed(UUID analysisId) {
        var work = analysisRepository.find(analysisId).orElseThrow();
        workspaceRepository.findForUpdate(work.workspaceId()).orElseThrow();
        work = analysisRepository.find(analysisId).orElseThrow();
        if (outboxRepository.analysisFailed(analysisId)
            && ("PENDING".equals(work.analysis().status()) || "RUNNING".equals(work.analysis().status()))) {
            analysisRepository.fail(analysisId, "AI_WORKER_LEASE_EXPIRED", true, clock.instant());
            notificationRepository.notifyMembers(work.workspaceId(), work.analysis().incidentId(),
                "AI_ANALYSIS_FAILED", clock.instant());
        }
    }

    private void requireAllowed(AnalysisDtos.AnalysisWork work) {
        if (!properties.enabled() || !work.sourceActive()) {
            throw new AnalysisFailure("AI_ACCESS_REVOKED", false);
        }
        var incident = incidentRepository.find(work.workspaceId(), work.analysis().incidentId()).orElseThrow();
        if (work.requestedBy() != null
            && !access.canAnalyze(work.requestedBy(), work.workspaceId(), incident.assignedTo())) {
            throw new AnalysisFailure("AI_ACCESS_REVOKED", false);
        }
    }
}

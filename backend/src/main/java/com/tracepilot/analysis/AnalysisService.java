package com.tracepilot.analysis;

import com.tracepilot.audit.AuditAction;
import com.tracepilot.audit.AuditLog;
import java.util.Map;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.workspace.WorkspaceRepository;
import com.tracepilot.knowledge.EvidenceRepository;
import com.tracepilot.knowledge.KnowledgeDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AnalysisService {
    private final AnalysisRepository analysisRepository;
    private final AnalysisAccess access;
    private final AnalysisInput input;
    private final OutboxRepository outboxRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AiProperties properties;
    private final Clock clock;
    private final AuditLog audit;
    private final EvidenceRepository evidenceRepository;

    public AnalysisService(AnalysisRepository analysisRepository, AnalysisAccess access, AnalysisInput input,
                           OutboxRepository outboxRepository, WorkspaceRepository workspaceRepository,
                           AiProperties properties, Clock clock, EvidenceRepository evidenceRepository, AuditLog audit) {
        this.analysisRepository = analysisRepository;
        this.access = access;
        this.input = input;
        this.outboxRepository = outboxRepository;
        this.workspaceRepository = workspaceRepository;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
        this.evidenceRepository = evidenceRepository;
    }

    @Transactional
    public AnalysisDtos.AnalysisResponse request(UUID actorId, UUID workspaceId, UUID incidentId, UUID requestId) {
        workspaceRepository.findForUpdate(workspaceId).orElseThrow(this::notFound);
        var incident = access.requireAnalyze(actorId, workspaceId, incidentId);
        var existing = analysisRepository.findRequest(workspaceId, requestId);
        if (existing.isPresent()) {
            if (!incidentId.equals(existing.orElseThrow().incidentId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request ID already belongs to another incident.");
            }
            return existing.orElseThrow();
        }
        requireEnabled();
        requireCapacity(workspaceId, incidentId);
        try {
            var prepared = input.load(incidentId);
            var id = UUID.randomUUID();
            analysisRepository.insert(id, workspaceId, incidentId, actorId, requestId, "MANUAL",
                properties.model(), prepared.sha256(), clock.instant());
            outboxRepository.enqueueAnalysis(incident.repositoryId(), id);
            audit.record(workspaceId, actorId, AuditAction.ANALYSIS_REQUESTED, id, Map.of("incidentId", incidentId));
            return analysisRepository.find(id).orElseThrow().analysis();
        } catch (AnalysisFailure exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Redacted logs are not available for analysis.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueAutomatic(UUID workspaceId, UUID incidentId, UUID repositoryId) {
        if (!properties.enabled() || analysisRepository.hasAutomatic(incidentId) || analysisRepository.hasActive(incidentId)
            || analysisRepository.callsSince(workspaceId, clock.instant().minus(Duration.ofDays(1))) >= properties.maxCallsPerDay()) {
            return;
        }
        AnalysisInput.PreparedInput prepared;
        try {
            prepared = input.load(incidentId);
        } catch (AnalysisFailure failure) {
            // Empty/non-analyzable logs must not roll back successful log capture.
            return;
        }
        var id = UUID.randomUUID();
        analysisRepository.insert(id, workspaceId, incidentId, null, UUID.randomUUID(), "AUTOMATIC",
            properties.model(), prepared.sha256(), clock.instant());
        outboxRepository.enqueueAnalysis(repositoryId, id);
    }

    public AnalysisDtos.AnalysisPage list(UUID actorId, UUID workspaceId, UUID incidentId, int page, int size) {
        access.requireRead(actorId, workspaceId, incidentId);
        return analysisRepository.list(workspaceId, incidentId, page, size);
    }

    public AnalysisDtos.AnalysisResponse get(UUID actorId, UUID workspaceId, UUID incidentId, UUID analysisId) {
        access.requireRead(actorId, workspaceId, incidentId);
        return analysisRepository.find(analysisId).filter(item -> item.workspaceId().equals(workspaceId)
            && item.analysis().incidentId().equals(incidentId)).orElseThrow(this::notFound).analysis();
    }

    public List<AnalysisDtos.UsageResponse> usage(UUID actorId, UUID workspaceId, UUID incidentId, UUID analysisId) {
        get(actorId, workspaceId, incidentId, analysisId);
        return analysisRepository.usage(analysisId);
    }

    public List<KnowledgeDtos.Evidence> evidence(UUID actorId, UUID workspaceId, UUID incidentId, UUID analysisId) {
        get(actorId, workspaceId, incidentId, analysisId);
        return evidenceRepository.list(analysisId);
    }

    private void requireCapacity(UUID workspaceId, UUID incidentId) {
        if (analysisRepository.hasActive(incidentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An analysis is already pending for this incident.");
        }
        if (analysisRepository.callsSince(workspaceId, clock.instant().minus(Duration.ofDays(1))) >= properties.maxCallsPerDay()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Workspace AI daily call limit reached.");
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI integration is not configured.");
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis or workspace membership not found.");
    }
}

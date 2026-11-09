package com.tracepilot.quality;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.tracepilot.analysis.AiProperties;
import com.tracepilot.analysis.AnalysisService;
import com.tracepilot.incident.LogRedactor;
import com.tracepilot.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class FeedbackService {
    private final FeedbackRepository repository;
    private final AnalysisService analyses;
    private final WorkspaceRepository workspaces;
    private final LogRedactor redactor;
    private final AiProperties ai;
    private final Clock clock;

    public FeedbackService(FeedbackRepository repository, AnalysisService analyses, WorkspaceRepository workspaces,
                           LogRedactor redactor, AiProperties ai, Clock clock) {
        this.repository = repository;
        this.analyses = analyses;
        this.workspaces = workspaces;
        this.redactor = redactor;
        this.ai = ai;
        this.clock = clock;
    }

    public QualityDtos.MyFeedback mine(UUID actorId, UUID workspaceId, UUID incidentId, UUID analysisId) {
        analyses.get(actorId, workspaceId, incidentId, analysisId);
        return new QualityDtos.MyFeedback(repository.find(workspaceId, analysisId, actorId).orElse(null));
    }

    @Transactional
    public QualityDtos.Feedback save(UUID actorId, UUID workspaceId, UUID incidentId, UUID analysisId,
                                     QualityDtos.FeedbackRequest request) {
        // Use the same lock as membership mutations: a removed member cannot commit a vote after removal.
        workspaces.findForUpdate(workspaceId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis or workspace membership not found."));
        var analysis = analyses.get(actorId, workspaceId, incidentId, analysisId);
        if (!"SUCCEEDED".equals(analysis.status())) {
            throw conflict("Only a completed successful report can receive feedback.");
        }
        if (!request.reason().supports(request.helpful())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feedback reason does not match the helpful vote.");
        }
        var clean = new QualityDtos.FeedbackRequest(request.helpful(), request.reason(),
            sanitize(request.comment()), request.version());
        var existing = repository.find(workspaceId, analysisId, actorId);
        if (existing.isEmpty()) {
            if (request.version() != 0) {
                throw conflict("Feedback changed. Reload before updating.");
            }
            repository.insert(workspaceId, incidentId, analysisId, actorId, clean, clock.instant());
        } else {
            var current = existing.orElseThrow();
            // Replaying an identical PUT is safe even if its original response was lost.
            if (current.helpful() == clean.helpful() && current.reason() == clean.reason()
                && Objects.equals(current.comment(), clean.comment())) {
                return current;
            }
            if (current.version() != request.version()) {
                throw conflict("Feedback changed. Reload before updating.");
            }
            repository.update(workspaceId, analysisId, actorId, clean, clock.instant());
        }
        return repository.find(workspaceId, analysisId, actorId).orElseThrow();
    }

    private String sanitize(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        var secrets = ai.apiKey() == null ? List.<String>of() : List.of(ai.apiKey());
        var clean = redactor.redact(comment, secrets).strip();
        if (clean.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feedback comment is too long after redaction.");
        }
        return clean.isBlank() ? null : clean;
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

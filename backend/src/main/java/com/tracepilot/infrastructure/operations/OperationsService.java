package com.tracepilot.infrastructure.operations;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.tracepilot.audit.AuditAction;
import com.tracepilot.audit.AuditLog;
import com.tracepilot.infrastructure.security.WorkspaceManagerAccess;
import com.tracepilot.pipeline.PipelineAccess;
import com.tracepilot.pipeline.PipelineProperties;
import com.tracepilot.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class OperationsService {
    private final OperationsRepository repository;
    private final WorkspaceManagerAccess access;
    private final WorkspaceRepository workspaces;
    private final PipelineAccess pipeline;
    private final PipelineProperties properties;
    private final AuditLog audit;
    private final Clock clock;

    public OperationsService(OperationsRepository repository, WorkspaceManagerAccess access,
                             WorkspaceRepository workspaces, PipelineAccess pipeline, PipelineProperties properties,
                             AuditLog audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.workspaces = workspaces;
        this.pipeline = pipeline;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    public List<OperationsDtos.Backlog> backlog(UUID actorId, UUID workspaceId) {
        access.require(actorId, workspaceId);
        return repository.backlog(workspaceId, clock.instant());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationsDtos.EventPage events(UUID actorId, UUID workspaceId, int page, int size) {
        access.require(actorId, workspaceId);
        return repository.list(workspaceId, page, size);
    }

    @Transactional
    public OperationsDtos.Event retry(UUID actorId, UUID workspaceId, UUID eventId, int replayCount) {
        workspaces.findForUpdate(workspaceId).orElseThrow(this::notFound);
        access.require(actorId, workspaceId);
        var event = repository.find(workspaceId, eventId).orElseThrow(this::notFound);
        if ("AI_ANALYSIS".equals(event.eventType())) {
            throw conflict("Request a new analysis to preserve report and usage history.");
        }
        if ("WORKFLOW_SYNC".equals(event.eventType())) {
            pipeline.requireOwner(actorId, workspaceId);
        }
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Pipeline integration is not configured.");
        }
        if (pipeline.currentScope(event.repositoryId()).isEmpty()) {
            throw conflict("Repository is inactive or installation is unavailable.");
        }
        if (!repository.retry(workspaceId, eventId, replayCount, clock.instant())) {
            throw conflict("Only a failed task delivered to the DLQ with the current replay count can be retried.");
        }
        audit.record(workspaceId, actorId, AuditAction.OUTBOX_REPLAYED, eventId,
            Map.of("replayCount", replayCount + 1, "previousAttempts", event.attempts()));
        return repository.find(workspaceId, eventId).orElseThrow();
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbox event or workspace not found.");
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

package com.tracepilot.incident;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.tracepilot.audit.AuditAction;
import com.tracepilot.audit.AuditLog;
import com.tracepilot.notification.NotificationRepository;
import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMember;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class IncidentService {
    private final IncidentRepository incidentRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final NotificationRepository notificationRepository;
    private final Clock clock;
    private final AuditLog audit;

    public IncidentService(IncidentRepository incidentRepository, WorkspaceRepository workspaceRepository,
                           WorkspaceMemberRepository memberRepository, NotificationRepository notificationRepository,
                           Clock clock, AuditLog audit) {
        this.incidentRepository = incidentRepository;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.notificationRepository = notificationRepository;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IncidentDtos.IncidentPage list(UUID actorId, UUID workspaceId, int page, int size, IncidentFilters filters) {
        requireMember(actorId, workspaceId);
        return incidentRepository.list(workspaceId, page, size, filters);
    }

    public IncidentDtos.IncidentResponse get(UUID actorId, UUID workspaceId, UUID incidentId) {
        requireMember(actorId, workspaceId);
        return find(workspaceId, incidentId);
    }

    public IncidentDtos.LogsResponse logs(UUID actorId, UUID workspaceId, UUID incidentId) {
        get(actorId, workspaceId, incidentId);
        return incidentRepository.logs(incidentId);
    }

    @Transactional
    public IncidentDtos.IncidentResponse changeStatus(UUID actorId, UUID workspaceId, UUID incidentId,
                                                      IncidentDtos.ChangeStatusRequest request) {
        lockWorkspace(workspaceId);
        var actor = requireMember(actorId, workspaceId);
        var incident = find(workspaceId, incidentId);
        if (actor.getRole() == WorkspaceRole.MEMBER && !actorId.equals(incident.assignedTo())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only managers or the assignee can change status.");
        }
        requireVersion(incident, request.version());
        if (!incident.status().canChangeTo(request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid incident status transition.");
        }
        if (incident.status() == request.status()) {
            return incident;
        }
        if (!incidentRepository.changeStatus(incidentId, request.version(), request.status(), clock.instant())) {
            throw staleVersion();
        }
        notificationRepository.notifyMembers(workspaceId, incidentId, "INCIDENT_STATUS_CHANGED", clock.instant());
        audit.record(workspaceId, actorId, AuditAction.INCIDENT_STATUS_CHANGED, incidentId,
            Map.of("fromStatus", incident.status(), "toStatus", request.status()));
        return find(workspaceId, incidentId);
    }

    @Transactional
    public IncidentDtos.IncidentResponse assign(UUID actorId, UUID workspaceId, UUID incidentId,
                                                IncidentDtos.AssignRequest request) {
        lockWorkspace(workspaceId);
        var actor = requireMember(actorId, workspaceId);
        var incident = find(workspaceId, incidentId);
        if (actor.getRole() == WorkspaceRole.MEMBER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only workspace managers can assign incidents.");
        }
        requireVersion(incident, request.version());
        if (request.assignedTo() != null) {
            requireMember(request.assignedTo(), workspaceId);
        }
        if (Objects.equals(incident.assignedTo(), request.assignedTo())) {
            return incident;
        }
        if (!incidentRepository.assign(incidentId, request.version(), request.assignedTo(), clock.instant())) {
            throw staleVersion();
        }
        notificationRepository.notifyMembers(workspaceId, incidentId, "INCIDENT_ASSIGNED", clock.instant());
        var metadata = new HashMap<String, Object>();
        metadata.put("assigned", request.assignedTo() != null);
        if (request.assignedTo() != null) {
            metadata.put("assigneeId", request.assignedTo());
        }
        if (incident.assignedTo() != null) {
            metadata.put("previousAssigneeId", incident.assignedTo());
        }
        audit.record(workspaceId, actorId, AuditAction.INCIDENT_ASSIGNED, incidentId, metadata);
        return find(workspaceId, incidentId);
    }

    private WorkspaceMember requireMember(UUID actorId, UUID workspaceId) {
        return memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(IncidentService::notFound);
    }

    private IncidentDtos.IncidentResponse find(UUID workspaceId, UUID incidentId) {
        return incidentRepository.find(workspaceId, incidentId).orElseThrow(IncidentService::notFound);
    }

    private void lockWorkspace(UUID workspaceId) {
        workspaceRepository.findForUpdate(workspaceId).orElseThrow(IncidentService::notFound);
    }

    private void requireVersion(IncidentDtos.IncidentResponse incident, long version) {
        if (incident.version() != version) {
            throw staleVersion();
        }
    }

    private static ResponseStatusException staleVersion() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Incident changed. Reload before updating.");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident or workspace membership not found.");
    }
}

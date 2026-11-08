package com.tracepilot.analysis;

import java.util.UUID;

import com.tracepilot.incident.IncidentDtos;
import com.tracepilot.incident.IncidentRepository;
import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisAccess {
    private final WorkspaceMemberRepository memberRepository;
    private final IncidentRepository incidentRepository;

    public AnalysisAccess(WorkspaceMemberRepository memberRepository, IncidentRepository incidentRepository) {
        this.memberRepository = memberRepository;
        this.incidentRepository = incidentRepository;
    }

    public IncidentDtos.IncidentResponse requireRead(UUID actorId, UUID workspaceId, UUID incidentId) {
        memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(AnalysisAccess::notFound);
        return incidentRepository.find(workspaceId, incidentId).orElseThrow(AnalysisAccess::notFound);
    }

    public IncidentDtos.IncidentResponse requireAnalyze(UUID actorId, UUID workspaceId, UUID incidentId) {
        var member = memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(AnalysisAccess::notFound);
        var incident = incidentRepository.find(workspaceId, incidentId).orElseThrow(AnalysisAccess::notFound);
        if (member.getRole() == WorkspaceRole.MEMBER && !actorId.equals(incident.assignedTo())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only managers or the assignee can request AI analysis.");
        }
        return incident;
    }

    public boolean canAnalyze(UUID actorId, UUID workspaceId, UUID assignedTo) {
        var member = memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE);
        return member.isPresent() && (member.orElseThrow().getRole() != WorkspaceRole.MEMBER || actorId.equals(assignedTo));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis or workspace membership not found.");
    }
}

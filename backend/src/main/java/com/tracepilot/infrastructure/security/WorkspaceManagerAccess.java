package com.tracepilot.infrastructure.security;

import java.util.UUID;

import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceManagerAccess {
    private final WorkspaceMemberRepository members;

    public WorkspaceManagerAccess(WorkspaceMemberRepository members) {
        this.members = members;
    }

    public void require(UUID actorId, UUID workspaceId) {
        var member = members.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace membership not found."));
        if (member.getRole() == WorkspaceRole.MEMBER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only workspace managers can view operations and audit.");
        }
    }
}

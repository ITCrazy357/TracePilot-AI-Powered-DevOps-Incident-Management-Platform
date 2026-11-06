package com.tracepilot.workspace;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class WorkspaceDtos {
    private WorkspaceDtos() {
    }

    public record CreateWorkspaceRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 120) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug) {
    }

    public record AddMemberRequest(@NotNull UUID userId, @NotNull WorkspaceRole role) {
    }

    public record ChangeRoleRequest(@NotNull WorkspaceRole role) {
    }

    public record WorkspaceResponse(UUID id, String name, String slug, UUID createdBy) {
        static WorkspaceResponse from(Workspace workspace) {
            return new WorkspaceResponse(workspace.getId(), workspace.getName(),
                workspace.getSlug(), workspace.getCreatedBy());
        }
    }

    public record MemberResponse(UUID userId, WorkspaceRole role, MembershipStatus status) {
        static MemberResponse from(WorkspaceMember member) {
            return new MemberResponse(member.getUserId(), member.getRole(), member.getStatus());
        }
    }
}

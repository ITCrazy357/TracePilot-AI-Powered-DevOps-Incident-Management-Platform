package com.tracepilot.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserIdAndStatus(
            UUID workspaceId, UUID userId, MembershipStatus status);

    List<WorkspaceMember> findByWorkspaceIdAndStatusOrderByJoinedAtAsc(UUID workspaceId, MembershipStatus status);
}

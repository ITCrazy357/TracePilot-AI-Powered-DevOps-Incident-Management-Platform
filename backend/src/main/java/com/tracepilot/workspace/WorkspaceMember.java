package com.tracepilot.workspace;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "workspace_members")
public class WorkspaceMember {
    @Id
    private UUID id;
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private WorkspaceRole role;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MembershipStatus status;
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected WorkspaceMember() {
    }

    public WorkspaceMember(UUID workspaceId, UUID userId, WorkspaceRole role, Instant now) {
        this.id = UUID.randomUUID();
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.role = role;
        this.status = MembershipStatus.ACTIVE;
        this.joinedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public void changeRole(WorkspaceRole role) {
        this.role = role;
    }

    public void remove() {
        this.status = MembershipStatus.REMOVED;
    }

    public void reactivate(WorkspaceRole role, Instant now) {
        this.role = role;
        this.status = MembershipStatus.ACTIVE;
        this.joinedAt = now;
    }
}

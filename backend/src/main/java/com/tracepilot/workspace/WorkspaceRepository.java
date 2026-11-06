package com.tracepilot.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    @Query("""
        select w from Workspace w join WorkspaceMember m on m.workspaceId = w.id
        where m.userId = :userId and m.status = com.tracepilot.workspace.MembershipStatus.ACTIVE
        order by w.createdAt, w.id
        """)
    List<Workspace> findAccessible(@Param("userId") UUID userId);

    // Serialize role/status mutations within each workspace so authorization checks
    // cannot race with removal or demotion of the acting administrator.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.id = :id")
    Optional<Workspace> findForUpdate(@Param("id") UUID id);
}

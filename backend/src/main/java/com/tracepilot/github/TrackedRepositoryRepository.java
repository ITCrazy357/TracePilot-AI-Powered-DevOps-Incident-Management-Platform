package com.tracepilot.github;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackedRepositoryRepository extends JpaRepository<TrackedRepository, UUID> {
    List<TrackedRepository> findByInstallationId(UUID installationId);

    Page<TrackedRepository> findByWorkspaceIdOrderByFullNameAscIdAsc(UUID workspaceId, Pageable pageable);
}

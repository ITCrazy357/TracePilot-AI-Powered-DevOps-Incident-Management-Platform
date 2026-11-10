package com.tracepilot.github;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubInstallationRepository extends JpaRepository<GitHubInstallation, UUID> {
    Optional<GitHubInstallation> findByWorkspaceId(UUID workspaceId);
    List<GitHubInstallation> findAllByGithubInstallationIdOrderByWorkspaceIdAsc(long githubInstallationId);
}

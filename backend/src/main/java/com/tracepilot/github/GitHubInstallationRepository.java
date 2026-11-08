package com.tracepilot.github;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubInstallationRepository extends JpaRepository<GitHubInstallation, UUID> {
    Optional<GitHubInstallation> findByGithubInstallationId(long githubInstallationId);
}

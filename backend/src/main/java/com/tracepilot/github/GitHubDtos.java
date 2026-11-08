package com.tracepilot.github;

import java.util.List;
import java.util.UUID;

public final class GitHubDtos {
    private GitHubDtos() {
    }

    public record RepositoryData(long id, String fullName, String defaultBranch) {
    }

    public record InstallationSnapshot(long id, String accountLogin, InstallationStatus status,
                                       List<RepositoryData> repositories) {
    }

    public record ConnectionResponse(UUID id, long installationId, String accountLogin,
                                     InstallationStatus status, int repositoryCount) {
    }

    public record RepositoryResponse(UUID id, long githubRepositoryId, String fullName,
                                     String defaultBranch, boolean active) {
        static RepositoryResponse from(TrackedRepository repository) {
            return new RepositoryResponse(repository.getId(), repository.getGithubRepositoryId(),
                repository.getFullName(), repository.getDefaultBranch(), repository.isActive());
        }
    }

    public record RepositoryPage(List<RepositoryResponse> items, int page, int size, long totalElements) {
    }

    public record WebhookResponse(String status, boolean duplicate) {
    }
}

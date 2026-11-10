package com.tracepilot.github;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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
                                     String defaultBranch, boolean active, boolean trackingEnabled) {
        static RepositoryResponse from(TrackedRepository repository) {
            return new RepositoryResponse(repository.getId(), repository.getGithubRepositoryId(),
                repository.getFullName(), repository.getDefaultBranch(), repository.isActive(), repository.isTrackingEnabled());
        }
    }

    public record InstallationChoice(long installationId, String accountLogin) {
    }

    public record ConnectionState(Long installationId, String accountLogin, Long version, List<Long> repositoryIds) {
    }

    public record ConnectionOptions(ConnectionState connection, List<InstallationChoice> installations) {
    }

    public record ConnectRequest(@Positive long installationId, @Min(0) Long version,
                                 @NotNull @Size(min = 1, max = 1000) List<@NotNull @Positive Long> repositoryIds) {
    }

    public record RepositoryPage(List<RepositoryResponse> items, int page, int size, long totalElements) {
    }

    public record WebhookResponse(String status, boolean duplicate) {
    }
}

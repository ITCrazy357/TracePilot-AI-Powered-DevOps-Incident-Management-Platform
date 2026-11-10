package com.tracepilot.github;

import com.tracepilot.audit.AuditAction;
import com.tracepilot.audit.AuditLog;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitHubConnectionStore {
    private final GitHubInstallationRepository installationRepository;
    private final TrackedRepositoryRepository repositoryRepository;
    private final GitHubBindingAccess access;
    private final AuditLog audit;
    private final Clock clock;

    public GitHubConnectionStore(GitHubInstallationRepository installationRepository,
                                 TrackedRepositoryRepository repositoryRepository,
                                 GitHubBindingAccess access, AuditLog audit, Clock clock) {
        this.installationRepository = installationRepository;
        this.repositoryRepository = repositoryRepository;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public GitHubDtos.ConnectionResponse applySnapshot(UUID actorId, UUID workspaceId,
                                                       GitHubDtos.InstallationSnapshot snapshot,
                                                       Optional<Long> expectedVersion, UUID sourceWorkspaceId,
                                                       Optional<Long> expectedSourceVersion, Optional<Set<Long>> selection) {
        // Remote I/O is complete; installation then ordered workspace locks serialize grants and webhooks.
        access.lockAndRequire(actorId, workspaceId, sourceWorkspaceId, snapshot.id());
        var sourceVersion = installationRepository.findByWorkspaceId(sourceWorkspaceId).map(GitHubInstallation::getVersion);
        if (!sourceVersion.equals(expectedSourceVersion)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub source permissions changed during synchronization. Retry.");
        }
        var now = clock.instant();
        var existingInstallation = installationRepository.findByWorkspaceId(workspaceId);
        if (!expectedVersion.equals(existingInstallation.map(GitHubInstallation::getVersion))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "GitHub connection changed during synchronization. Retry.");
        }
        var installation = existingInstallation
            .orElseGet(() -> new GitHubInstallation(workspaceId, snapshot.id(), snapshot.accountLogin(), now));
        if (installation.getGithubInstallationId() != snapshot.id()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This workspace already uses another GitHub installation.");
        }
        installation.update(snapshot.accountLogin(), snapshot.status(), now);
        installation = installationRepository.saveAndFlush(installation);

        var existing = repositoryRepository.findByInstallationId(installation.getId());
        var chosen = selection.orElseGet(() -> selectedRepositories(existingInstallation.isEmpty(), snapshot, existing));
        if (selection.isPresent() && !snapshot.repositories().stream().map(GitHubDtos.RepositoryData::id).collect(Collectors.toSet()).containsAll(chosen)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub repository permissions changed. Reload the selection.");
        }
        var byGitHubId = new HashMap<Long, TrackedRepository>();
        for (var repository : existing) {
            repository.setActive(false, now);
            repository.setTrackingEnabled(chosen.contains(repository.getGithubRepositoryId()), now);
            byGitHubId.put(repository.getGithubRepositoryId(), repository);
        }
        for (var data : snapshot.repositories()) {
            if (!chosen.contains(data.id())) {
                continue;
            }
            var repository = byGitHubId.get(data.id());
            if (repository == null) {
                repository = new TrackedRepository(installation, data, now);
                repositoryRepository.save(repository);
            } else {
                repository.update(data, true, now);
            }
        }
        audit.record(workspaceId, actorId, AuditAction.GITHUB_CONNECTION_UPDATED, installation.getId(),
            Map.of("installationId", snapshot.id(), "selectedRepositories", chosen.size(), "sourceWorkspaceId", sourceWorkspaceId));
        return new GitHubDtos.ConnectionResponse(installation.getId(), snapshot.id(), snapshot.accountLogin(),
            snapshot.status(), chosen.size());
    }

    private Set<Long> selectedRepositories(boolean bootstrap, GitHubDtos.InstallationSnapshot snapshot,
                                            List<TrackedRepository> existing) {
        if (bootstrap) {
            return snapshot.repositories().stream().map(GitHubDtos.RepositoryData::id).collect(Collectors.toSet());
        }
        return existing.stream().filter(TrackedRepository::isTrackingEnabled).map(TrackedRepository::getGithubRepositoryId)
            .collect(Collectors.toSet());
    }
}

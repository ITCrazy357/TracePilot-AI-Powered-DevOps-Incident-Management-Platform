package com.tracepilot.github;

import java.time.Clock;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitHubConnectionStore {
    private final GitHubInstallationRepository installationRepository;
    private final TrackedRepositoryRepository repositoryRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final Clock clock;

    public GitHubConnectionStore(GitHubInstallationRepository installationRepository,
                                 TrackedRepositoryRepository repositoryRepository,
                                 WorkspaceRepository workspaceRepository,
                                 WorkspaceMemberRepository memberRepository, Clock clock) {
        this.installationRepository = installationRepository;
        this.repositoryRepository = repositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.clock = clock;
    }

    @Transactional
    public GitHubDtos.ConnectionResponse applySnapshot(UUID actorId, UUID workspaceId,
                                                       GitHubDtos.InstallationSnapshot snapshot,
                                                       Optional<Long> expectedVersion) {
        // The remote request finishes before entering this transaction. This lock
        // serializes synchronization with membership changes and webhook updates.
        workspaceRepository.findForUpdate(workspaceId).orElseThrow(this::notFound);
        var membership = memberRepository.findByWorkspaceIdAndUserIdAndStatus(
            workspaceId, actorId, MembershipStatus.ACTIVE).orElseThrow(this::notFound);
        if (membership.getRole() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the workspace owner can connect GitHub.");
        }
        var now = clock.instant();
        var existingInstallation = installationRepository.findByGithubInstallationId(snapshot.id());
        if (!expectedVersion.equals(existingInstallation.map(GitHubInstallation::getVersion))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "GitHub connection changed during synchronization. Retry.");
        }
        var installation = existingInstallation
            .orElseGet(() -> new GitHubInstallation(workspaceId, snapshot.id(), snapshot.accountLogin(), now));
        if (!installation.getWorkspaceId().equals(workspaceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Installation is already bound to another workspace.");
        }
        installation.update(snapshot.accountLogin(), snapshot.status(), now);
        installation = installationRepository.saveAndFlush(installation);

        var existing = repositoryRepository.findByInstallationId(installation.getId());
        var byGitHubId = new HashMap<Long, TrackedRepository>();
        for (var repository : existing) {
            repository.setActive(false, now);
            byGitHubId.put(repository.getGithubRepositoryId(), repository);
        }
        for (var data : snapshot.repositories()) {
            var repository = byGitHubId.get(data.id());
            if (repository == null) {
                repository = new TrackedRepository(installation, data, now);
                repositoryRepository.save(repository);
            } else {
                repository.update(data, true, now);
            }
        }
        return new GitHubDtos.ConnectionResponse(installation.getId(), snapshot.id(), snapshot.accountLogin(),
            snapshot.status(), snapshot.repositories().size());
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace or membership not found.");
    }
}

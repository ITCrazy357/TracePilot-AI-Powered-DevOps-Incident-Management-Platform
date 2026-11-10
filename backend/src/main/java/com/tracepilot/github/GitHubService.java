package com.tracepilot.github;

import java.util.UUID;
import java.util.Optional;
import java.util.HashSet;
import java.util.List;

import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMember;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitHubService {
    private final GitHubProperties properties;
    private final GitHubApiClient apiClient;
    private final GitHubConnectionStore connectionStore;
    private final WorkspaceMemberRepository memberRepository;
    private final TrackedRepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final GitHubBindingAccess access;

    public GitHubService(GitHubProperties properties, GitHubApiClient apiClient,
                         GitHubConnectionStore connectionStore, WorkspaceMemberRepository memberRepository,
                         TrackedRepositoryRepository repositoryRepository,
                         GitHubInstallationRepository installationRepository, GitHubBindingAccess access) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.connectionStore = connectionStore;
        this.memberRepository = memberRepository;
        this.repositoryRepository = repositoryRepository;
        this.installationRepository = installationRepository;
        this.access = access;
    }

    public GitHubDtos.ConnectionResponse synchronize(UUID actorId, UUID workspaceId) {
        var membership = requireMembership(actorId, workspaceId);
        if (membership.getRole() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the workspace owner can connect GitHub.");
        }
        var connection = installationRepository.findByWorkspaceId(workspaceId);
        long installationId;
        if (connection.isPresent()) {
            installationId = connection.orElseThrow().getGithubInstallationId();
        } else {
            properties.requireWorkspace(workspaceId);
            installationId = properties.installationId();
        }
        var source = access.authorizedSource(actorId, installationId);
        var sourceVersion = installationRepository.findByWorkspaceId(source).map(GitHubInstallation::getVersion);
        var snapshot = apiClient.fetchInstallation(installationId);
        return connectionStore.applySnapshot(actorId, workspaceId, snapshot,
            connection.map(GitHubInstallation::getVersion), source, sourceVersion, Optional.empty());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GitHubDtos.ConnectionOptions options(UUID actorId, UUID workspaceId) {
        access.requireOwner(actorId, workspaceId);
        properties.requireEnabled();
        var state = installationRepository.findByWorkspaceId(workspaceId).map(item -> new GitHubDtos.ConnectionState(
            item.getGithubInstallationId(), item.getAccountLogin(), item.getVersion(),
            repositoryRepository.findByInstallationId(item.getId()).stream().filter(TrackedRepository::isTrackingEnabled)
                .map(TrackedRepository::getGithubRepositoryId).toList()))
            .orElseGet(() -> new GitHubDtos.ConnectionState(null, null, null, List.of()));
        return new GitHubDtos.ConnectionOptions(state, access.choices(actorId));
    }

    public GitHubDtos.InstallationSnapshot catalog(UUID actorId, UUID workspaceId, long installationId) {
        access.requireOwner(actorId, workspaceId);
        var source = access.authorizedSource(actorId, installationId);
        var snapshot = apiClient.fetchInstallation(installationId);
        access.recheck(actorId, workspaceId, source, installationId);
        return snapshot;
    }

    public GitHubDtos.ConnectionResponse connect(UUID actorId, UUID workspaceId, GitHubDtos.ConnectRequest request) {
        access.requireOwner(actorId, workspaceId);
        var source = access.authorizedSource(actorId, request.installationId());
        var selected = new HashSet<>(request.repositoryIds());
        if (selected.size() != request.repositoryIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repository selection contains duplicates.");
        }
        var current = installationRepository.findByWorkspaceId(workspaceId);
        if (!current.map(GitHubInstallation::getVersion).equals(Optional.ofNullable(request.version()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub connection changed. Reload before saving.");
        }
        var sourceVersion = installationRepository.findByWorkspaceId(source).map(GitHubInstallation::getVersion);
        var snapshot = apiClient.fetchInstallation(request.installationId());
        return connectionStore.applySnapshot(actorId, workspaceId, snapshot, Optional.ofNullable(request.version()),
            source, sourceVersion, Optional.of(selected));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GitHubDtos.RepositoryPage repositories(UUID actorId, UUID workspaceId, int page, int size) {
        requireMembership(actorId, workspaceId);
        var result = repositoryRepository.findByWorkspaceIdOrderByFullNameAscIdAsc(
            workspaceId, PageRequest.of(page, size));
        var items = result.getContent().stream().map(GitHubDtos.RepositoryResponse::from).toList();
        return new GitHubDtos.RepositoryPage(items, page, size, result.getTotalElements());
    }

    private WorkspaceMember requireMembership(UUID actorId, UUID workspaceId) {
        return memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace or membership not found."));
    }
}

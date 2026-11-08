package com.tracepilot.github;

import java.util.UUID;

import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMember;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    public GitHubService(GitHubProperties properties, GitHubApiClient apiClient,
                         GitHubConnectionStore connectionStore, WorkspaceMemberRepository memberRepository,
                         TrackedRepositoryRepository repositoryRepository,
                         GitHubInstallationRepository installationRepository) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.connectionStore = connectionStore;
        this.memberRepository = memberRepository;
        this.repositoryRepository = repositoryRepository;
        this.installationRepository = installationRepository;
    }

    public GitHubDtos.ConnectionResponse synchronize(UUID actorId, UUID workspaceId) {
        var membership = requireMembership(actorId, workspaceId);
        if (membership.getRole() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the workspace owner can connect GitHub.");
        }
        properties.requireWorkspace(workspaceId);
        var version = installationRepository.findByGithubInstallationId(properties.installationId())
            .map(GitHubInstallation::getVersion);
        var snapshot = apiClient.fetchInstallation();
        return connectionStore.applySnapshot(actorId, workspaceId, snapshot, version);
    }

    @Transactional(readOnly = true)
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

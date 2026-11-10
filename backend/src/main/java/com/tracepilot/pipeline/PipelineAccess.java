package com.tracepilot.pipeline;

import java.util.Optional;
import java.util.UUID;

import com.tracepilot.github.GitHubInstallationRepository;
import com.tracepilot.github.GitHubProperties;
import com.tracepilot.github.InstallationStatus;
import com.tracepilot.github.TrackedRepository;
import com.tracepilot.github.TrackedRepositoryRepository;
import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class PipelineAccess {
    private final WorkspaceMemberRepository memberRepository;
    private final TrackedRepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final GitHubProperties properties;

    public PipelineAccess(WorkspaceMemberRepository memberRepository, TrackedRepositoryRepository repositoryRepository,
                          GitHubInstallationRepository installationRepository, GitHubProperties properties) {
        this.memberRepository = memberRepository;
        this.repositoryRepository = repositoryRepository;
        this.installationRepository = installationRepository;
        this.properties = properties;
    }

    public TrackedRepository requireRead(UUID actorId, UUID workspaceId, UUID repositoryId) {
        memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(this::notFound);
        return repositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId).orElseThrow(this::notFound);
    }

    public void requireOwner(UUID actorId, UUID workspaceId) {
        var member = memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(this::notFound);
        if (member.getRole() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only the workspace owner can request synchronization.");
        }
    }

    public Optional<PipelineDtos.RepositoryScope> currentScope(UUID repositoryId) {
        var repository = repositoryRepository.findById(repositoryId);
        if (repository.isEmpty() || !repository.orElseThrow().isActive() || !properties.enabled()) {
            return Optional.empty();
        }
        var current = repository.orElseThrow();
        var installation = installationRepository.findById(current.getInstallationId());
        if (installation.isEmpty() || installation.orElseThrow().getStatus() != InstallationStatus.ACTIVE
            || !installation.orElseThrow().getWorkspaceId().equals(current.getWorkspaceId())) {
            return Optional.empty();
        }
        return Optional.of(new PipelineDtos.RepositoryScope(current.getId(), current.getWorkspaceId(),
            current.getInstallationId(), current.getGithubRepositoryId(), current.getFullName(),
            installation.orElseThrow().getVersion(), installation.orElseThrow().getGithubInstallationId()));
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository or workspace membership not found.");
    }
}

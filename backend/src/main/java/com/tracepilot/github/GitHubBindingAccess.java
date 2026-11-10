package com.tracepilot.github;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitHubBindingAccess {
    private final JdbcTemplate jdbc;
    private final GitHubProperties properties;
    private final WorkspaceMemberRepository members;
    private final WorkspaceRepository workspaces;

    public GitHubBindingAccess(JdbcTemplate jdbc, GitHubProperties properties,
                              WorkspaceMemberRepository members, WorkspaceRepository workspaces) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.members = members;
        this.workspaces = workspaces;
    }

    public void requireOwner(UUID actorId, UUID workspaceId) {
        var member = members.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(this::notFound);
        if (member.getRole() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the workspace owner can manage GitHub.");
        }
    }

    public UUID authorizedSource(UUID actorId, long installationId) {
        properties.requireEnabled();
        var sources = jdbc.queryForList("""
            SELECT g.workspace_id FROM github_installations g JOIN workspace_members m ON m.workspace_id = g.workspace_id
            WHERE g.github_installation_id = ? AND g.status <> 'REMOVED'
                AND m.user_id = ? AND m.role = 'OWNER' AND m.status = 'ACTIVE'
            ORDER BY g.workspace_id LIMIT 1
            """, UUID.class, installationId, actorId);
        if (!sources.isEmpty()) {
            return sources.getFirst();
        }
        if (installationId == properties.installationId() && isBootstrapOwner(actorId)) {
            return properties.workspaceId();
        }
        throw notFound();
    }

    public List<GitHubDtos.InstallationChoice> choices(UUID actorId) {
        var choices = new ArrayList<>(jdbc.query("""
            SELECT DISTINCT ON (g.github_installation_id) g.github_installation_id, g.account_login FROM github_installations g
            JOIN workspace_members m ON m.workspace_id = g.workspace_id
            WHERE m.user_id = ? AND m.role = 'OWNER' AND m.status = 'ACTIVE' AND g.status <> 'REMOVED'
            ORDER BY g.github_installation_id, g.updated_at DESC
            """, (row, number) -> new GitHubDtos.InstallationChoice(row.getLong(1), row.getString(2)), actorId));
        if (properties.enabled() && isBootstrapOwner(actorId)
            && choices.stream().noneMatch(item -> item.installationId() == properties.installationId())) {
            choices.add(new GitHubDtos.InstallationChoice(properties.installationId(), "GitHub App đã cấu hình"));
        }
        return List.copyOf(choices);
    }

    // Serialize a connection change and webhook fan-out before taking workspace locks.
    public void lockInstallation(long installationId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('tracepilot-github:' || CAST(? AS text), 0))",
            (row, number) -> 0, installationId);
    }

    public void lockWebhookTargets(long installationId) {
        lockInstallation(installationId);
        var targets = jdbc.queryForList("SELECT workspace_id FROM github_installations WHERE github_installation_id = ? ORDER BY workspace_id",
            UUID.class, installationId);
        for (var workspaceId : targets) {
            workspaces.findForUpdate(workspaceId).orElseThrow(this::notFound);
        }
    }

    @Transactional
    public void recheck(UUID actorId, UUID targetId, UUID sourceId, long installationId) {
        lockAndRequire(actorId, targetId, sourceId, installationId);
    }

    public void lockAndRequire(UUID actorId, UUID targetId, UUID sourceId, long installationId) {
        lockInstallation(installationId);
        for (var id : List.of(targetId, sourceId).stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList()) {
            workspaces.findForUpdate(id).orElseThrow(this::notFound);
        }
        requireOwner(actorId, targetId);
        requireOwner(actorId, sourceId);
        var authorized = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM github_installations WHERE workspace_id = ?
                AND github_installation_id = ? AND status <> 'REMOVED')
            """, Boolean.class, sourceId, installationId);
        boolean bootstrap = sourceId.equals(properties.workspaceId()) && installationId == properties.installationId();
        if (!Boolean.TRUE.equals(authorized) && !bootstrap) {
            throw notFound();
        }
    }

    private boolean isBootstrapOwner(UUID actorId) {
        return properties.workspaceId() != null && members.findByWorkspaceIdAndUserIdAndStatus(
            properties.workspaceId(), actorId, MembershipStatus.ACTIVE).filter(item -> item.getRole() == WorkspaceRole.OWNER).isPresent();
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "GitHub installation or workspace access not found.");
    }
}

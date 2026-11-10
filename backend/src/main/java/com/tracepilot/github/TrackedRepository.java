package com.tracepilot.github;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "repositories")
public class TrackedRepository {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;
    @Column(name = "installation_id", nullable = false)
    private UUID installationId;
    @Column(name = "github_repository_id", nullable = false)
    private long githubRepositoryId;
    @Column(name = "full_name", nullable = false, length = 300)
    private String fullName;
    @Column(name = "default_branch", length = 255)
    private String defaultBranch;
    @Column(name = "is_active", nullable = false)
    private boolean active;
    @Column(name = "tracking_enabled", nullable = false)
    private boolean trackingEnabled = true;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TrackedRepository() {
    }

    public TrackedRepository(GitHubInstallation installation, GitHubDtos.RepositoryData data, Instant now) {
        this.workspaceId = installation.getWorkspaceId();
        this.installationId = installation.getId();
        this.githubRepositoryId = data.id();
        this.createdAt = now;
        update(data, true, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getInstallationId() {
        return installationId;
    }

    public long getGithubRepositoryId() {
        return githubRepositoryId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    public void setTrackingEnabled(boolean enabled, Instant now) {
        trackingEnabled = enabled;
        if (!enabled) {
            setActive(false, now);
        }
    }

    public void update(GitHubDtos.RepositoryData data, boolean active, Instant now) {
        this.fullName = data.fullName();
        if (data.defaultBranch() != null) {
            this.defaultBranch = data.defaultBranch();
        }
        setActive(active, now);
    }

    public void setActive(boolean active, Instant now) {
        this.active = active && trackingEnabled;
        this.updatedAt = now;
    }
}

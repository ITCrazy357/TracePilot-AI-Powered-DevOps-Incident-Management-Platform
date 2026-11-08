package com.tracepilot.github;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "github_installations")
public class GitHubInstallation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;
    @Column(name = "github_installation_id", nullable = false)
    private long githubInstallationId;
    @Column(name = "account_login", nullable = false, length = 255)
    private String accountLogin;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InstallationStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected GitHubInstallation() {
    }

    public GitHubInstallation(UUID workspaceId, long githubInstallationId, String accountLogin, Instant now) {
        this.workspaceId = workspaceId;
        this.githubInstallationId = githubInstallationId;
        this.accountLogin = accountLogin;
        this.status = InstallationStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public long getGithubInstallationId() {
        return githubInstallationId;
    }

    public String getAccountLogin() {
        return accountLogin;
    }

    public InstallationStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public void update(String accountLogin, InstallationStatus status, Instant now) {
        this.accountLogin = accountLogin;
        this.status = status;
        this.updatedAt = now;
    }
}

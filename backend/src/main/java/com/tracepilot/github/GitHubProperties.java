package com.tracepilot.github;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ConfigurationProperties("tracepilot.github")
public record GitHubProperties(boolean enabled, long appId, long installationId, UUID workspaceId,
                               Path privateKeyPath, String webhookSecret, URI apiBaseUrl) {
    public GitHubProperties {
        if (enabled && (appId <= 0 || installationId <= 0 || workspaceId == null || privateKeyPath == null
            || webhookSecret == null || webhookSecret.length() < 32)) {
            throw new IllegalArgumentException(
                "GitHub requires app/installation IDs, workspace, key path and a 32+ character secret.");
        }
        boolean local = "127.0.0.1".equals(apiBaseUrl.getHost()) || "localhost".equals(apiBaseUrl.getHost());
        if (!("https".equals(apiBaseUrl.getScheme()) || (local && "http".equals(apiBaseUrl.getScheme())))
            || apiBaseUrl.getUserInfo() != null || apiBaseUrl.getQuery() != null || apiBaseUrl.getFragment() != null) {
            throw new IllegalArgumentException("GitHub API URL must use HTTPS (HTTP loopback is allowed for tests).");
        }
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub integration is not configured.");
        }
    }

    public void requireWorkspace(UUID requestedWorkspaceId) {
        requireEnabled();
        if (!workspaceId.equals(requestedWorkspaceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This workspace has no operator-approved GitHub binding.");
        }
    }
}

package com.tracepilot.github;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class GitHubController {
    private final GitHubService gitHubService;

    public GitHubController(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    @PostMapping("/github/sync")
    public GitHubDtos.ConnectionResponse synchronize(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable UUID workspaceId) {
        return gitHubService.synchronize(UUID.fromString(jwt.getSubject()), workspaceId);
    }

    @GetMapping("/repositories")
    public GitHubDtos.RepositoryPage repositories(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                  @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return gitHubService.repositories(UUID.fromString(jwt.getSubject()), workspaceId, page, size);
    }

    @GetMapping("/github/connection")
    public GitHubDtos.ConnectionOptions options(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return gitHubService.options(UUID.fromString(jwt.getSubject()), workspaceId);
    }

    @GetMapping("/github/installations/{installationId}/repositories")
    public GitHubDtos.InstallationSnapshot catalog(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                    @PathVariable @Positive long installationId) {
        return gitHubService.catalog(UUID.fromString(jwt.getSubject()), workspaceId, installationId);
    }

    @PostMapping("/github/connection")
    public GitHubDtos.ConnectionResponse connect(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                 @Valid @RequestBody GitHubDtos.ConnectRequest request) {
        return gitHubService.connect(UUID.fromString(jwt.getSubject()), workspaceId, request);
    }
}

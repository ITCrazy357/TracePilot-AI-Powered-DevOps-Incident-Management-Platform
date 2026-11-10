package com.tracepilot.github;

import com.tracepilot.common.LimitedResponseBody;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GitHubApiClient {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_REPOSITORIES = 1000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String TOKEN_PERMISSIONS = "{\"permissions\":{\"metadata\":\"read\",\"actions\":\"read\"}}";
    private final GitHubProperties properties;
    private final GitHubAppTokenSigner tokenSigner;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitHubApiClient(GitHubProperties properties, GitHubAppTokenSigner tokenSigner,
                           HttpClient gitHubHttpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenSigner = tokenSigner;
        this.httpClient = gitHubHttpClient;
        this.objectMapper = objectMapper;
    }

    public GitHubDtos.InstallationSnapshot fetchInstallation(long installationId) {
        var appToken = tokenSigner.createToken();
        var installation = request("GET", "/app/installations/" + installationId, appToken);
        try {
            if (GitHubPayloads.positiveId(installation.path("id")) != installationId
                || GitHubPayloads.positiveId(installation.path("app_id")) != properties.appId()) {
                throw new IllegalArgumentException("Installation does not belong to the configured app.");
            }
            var account = GitHubPayloads.text(installation.path("account").path("login"), 255);
            if (!installation.path("suspended_at").isNull()) {
                return new GitHubDtos.InstallationSnapshot(installationId, account, InstallationStatus.SUSPENDED,
                    List.of());
            }
            var credentials = request("POST", "/app/installations/" + installationId + "/access_tokens", appToken);
            var installationToken = GitHubPayloads.text(credentials.path("token"), 8192);
            return new GitHubDtos.InstallationSnapshot(installationId, account, InstallationStatus.ACTIVE,
                fetchRepositories(installationToken));
        } catch (IllegalArgumentException exception) {
            throw upstreamFailure();
        }
    }

    String createInstallationToken(long installationId) {
        properties.requireEnabled();
        var response = request("POST", "/app/installations/" + installationId + "/access_tokens",
            tokenSigner.createToken());
        try {
            return GitHubPayloads.text(response.path("token"), 8192);
        } catch (IllegalArgumentException exception) {
            throw upstreamFailure();
        }
    }

    JsonNode getRepositoryResource(String path, String token) {
        if (!path.startsWith("/repos/")) {
            throw new IllegalArgumentException("Expected a repository API path.");
        }
        return request("GET", path, token);
    }

    private List<GitHubDtos.RepositoryData> fetchRepositories(String token) {
        var repositories = new ArrayList<GitHubDtos.RepositoryData>();
        var seenIds = new HashSet<Long>();
        long expectedCount = -1;
        for (int page = 1; page <= MAX_REPOSITORIES / PAGE_SIZE + 1; page++) {
            var result = request("GET", "/installation/repositories?per_page=" + PAGE_SIZE + "&page=" + page, token);
            var items = result.path("repositories");
            var count = result.path("total_count");
            if (!items.isArray() || items.size() > PAGE_SIZE || !count.isIntegralNumber()
                || !count.canConvertToLong() || count.asLong() < 0 || count.asLong() > MAX_REPOSITORIES) {
                throw upstreamFailure();
            }
            if (expectedCount != -1 && expectedCount != count.asLong()) {
                throw upstreamFailure();
            }
            expectedCount = count.asLong();
            for (var item : items) {
                var repository = GitHubPayloads.repository(item);
                if (!seenIds.add(repository.id()) || repositories.size() >= MAX_REPOSITORIES) {
                    throw upstreamFailure();
                }
                repositories.add(repository);
            }
            if (items.size() < PAGE_SIZE) {
                if (repositories.size() != expectedCount) {
                    throw upstreamFailure();
                }
                return List.copyOf(repositories);
            }
        }
        throw upstreamFailure();
    }

    private JsonNode request(String method, String path, String token) {
        int attempts = "GET".equals(method) ? 3 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            var builder = HttpRequest.newBuilder(properties.apiBaseUrl().resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2026-03-10");
            var request = "GET".equals(method) ? builder.GET().build()
                : builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TOKEN_PERMISSIONS))
                .build();
            try {
                var response = send(request);
                if (response.statusCode() >= 500 && attempt < attempts) {
                    pauseBeforeRetry(attempt);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw upstreamFailure();
                }
                var body = objectMapper.readTree(response.body());
                if (body == null || !body.isObject()) {
                    throw upstreamFailure();
                }
                return body;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw upstreamFailure();
            } catch (IOException exception) {
                if (attempt == attempts) {
                    throw upstreamFailure();
                }
                pauseBeforeRetry(attempt);
            } catch (tools.jackson.core.JacksonException exception) {
                throw upstreamFailure();
            }
        }
        throw upstreamFailure();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        var response = httpClient.sendAsync(request, info -> new LimitedResponseBody(MAX_RESPONSE_BYTES));
        try {
            // The deadline covers the complete body, not just connecting/receiving headers.
            return response.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            response.cancel(true);
            throw new IOException("GitHub response timed out.", exception);
        } catch (ExecutionException exception) {
            throw new IOException("GitHub response could not be read.", exception);
        } catch (InterruptedException exception) {
            response.cancel(true);
            throw exception;
        }
    }

    private void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(100L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw upstreamFailure();
        }
    }

    private ResponseStatusException upstreamFailure() {
        // Provider bodies can contain credentials; return a fixed message instead.
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "GitHub API request failed. Retry synchronization later.");
    }
}

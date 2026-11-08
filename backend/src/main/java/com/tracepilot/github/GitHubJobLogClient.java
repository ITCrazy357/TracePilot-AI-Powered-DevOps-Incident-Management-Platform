package com.tracepilot.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.tracepilot.incident.LogRedactor;
import com.tracepilot.pipeline.PipelineDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GitHubJobLogClient {
    private static final int MAX_LOG_BYTES = 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final GitHubApiClient apiClient;
    private final GitHubProperties properties;
    private final HttpClient httpClient;
    private final LogRedactor redactor;

    public GitHubJobLogClient(GitHubApiClient apiClient, GitHubProperties properties,
                              HttpClient gitHubHttpClient, LogRedactor redactor) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.httpClient = gitHubHttpClient;
        this.redactor = redactor;
    }

    public String fetchRedacted(PipelineDtos.RepositoryScope scope, long githubJobId) {
        try {
            properties.requireWorkspace(scope.workspaceId());
            if (!scope.fullName().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || githubJobId <= 0) {
                throw failure();
            }
            var token = apiClient.createInstallationToken();
            var apiUrl = properties.apiBaseUrl().resolve(
                "/repos/" + scope.fullName() + "/actions/jobs/" + githubJobId + "/logs");
            var redirect = send(HttpRequest.newBuilder(apiUrl).timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("Authorization", "Bearer " + token).GET().build());
            if (redirect.statusCode() != 302) {
                throw failure();
            }
            var downloadUrl = URI.create(redirect.headers().firstValue("Location").orElseThrow());
            requireSafeDownload(downloadUrl);
            // Signed download URLs are short-lived credentials. Do not forward the installation token.
            var response = send(HttpRequest.newBuilder(downloadUrl).timeout(TIMEOUT).GET().build());
            if (response.statusCode() != 200) {
                throw failure();
            }
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            if (text.indexOf('\0') >= 0) {
                throw failure();
            }
            return redactor.redact(text, List.of(token));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure();
        } catch (ExecutionException | TimeoutException | CharacterCodingException | IllegalArgumentException
                 | java.util.NoSuchElementException exception) {
            // Never retain provider bodies, raw log text, or signed URLs in an exception.
            throw failure();
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request)
        throws InterruptedException, ExecutionException, TimeoutException {
        var future = httpClient.sendAsync(request, info -> new LimitedResponseBody(MAX_LOG_BYTES));
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException exception) {
            future.cancel(true);
            throw exception;
        }
    }

    private void requireSafeDownload(URI url) {
        if (url.getUserInfo() != null || url.getFragment() != null || url.getHost() == null) {
            throw failure();
        }
        var host = url.getHost().toLowerCase(Locale.ROOT);
        boolean githubStorage = "https".equals(url.getScheme()) && (url.getPort() == -1 || url.getPort() == 443)
            && (host.endsWith(".blob.core.windows.net") || host.endsWith(".actions.githubusercontent.com"));
        var base = properties.apiBaseUrl();
        boolean localFixture = "http".equals(base.getScheme())
            && ("127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost()))
            && base.getScheme().equals(url.getScheme()) && base.getHost().equals(url.getHost())
            && base.getPort() == url.getPort();
        if (!githubStorage && !localFixture) {
            throw failure();
        }
    }

    private ResponseStatusException failure() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub job logs could not be captured.");
    }
}

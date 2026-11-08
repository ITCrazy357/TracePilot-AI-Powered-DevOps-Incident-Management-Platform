package com.tracepilot.github;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.tracepilot.pipeline.PipelineDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

@Component
public class GitHubWorkflowClient {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_JOBS = 1000;
    private final GitHubApiClient apiClient;

    public GitHubWorkflowClient(GitHubApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public PipelineDtos.Snapshot fetch(PipelineDtos.RepositoryScope scope, PipelineDtos.Work work) {
        var token = apiClient.createInstallationToken();
        var path = "/repos/" + scope.fullName() + "/actions/runs/" + work.runId() + "/attempts/" + work.runAttempt();
        try {
            var run = apiClient.getRepositoryResource(path, token);
            if (GitHubPayloads.positiveId(run.path("id")) != work.runId()
                || GitHubPayloads.positiveId(run.path("run_attempt")) != work.runAttempt()
                || GitHubPayloads.positiveId(run.path("repository").path("id")) != scope.githubRepositoryId()) {
                throw new IllegalArgumentException("Workflow identity mismatch.");
            }
            var name = run.hasNonNull("name") ? run.path("name") : run.path("path");
            var data = new PipelineDtos.RunData(work.runId(), work.runAttempt(),
                GitHubPayloads.positiveId(run.path("workflow_id")), GitHubPayloads.text(name, 255),
                optionalText(run.path("head_branch"), 255), optionalText(run.path("head_sha"), 64),
                GitHubPayloads.text(run.path("status"), 40), optionalText(run.path("conclusion"), 40),
                htmlUrl(run.path("html_url"), scope.fullName()), timestamp(run.path("run_started_at")),
                timestamp(run.path("completed_at")), Instant.parse(GitHubPayloads.text(run.path("updated_at"), 40)));
            return new PipelineDtos.Snapshot(data, jobs(path + "/jobs", token, work, scope.fullName()));
        } catch (IllegalArgumentException | JacksonException | DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid GitHub workflow snapshot.");
        }
    }

    private List<PipelineDtos.JobData> jobs(String path, String token, PipelineDtos.Work work, String repositoryName) {
        var jobs = new ArrayList<PipelineDtos.JobData>();
        var ids = new HashSet<Long>();
        long expectedCount = -1;
        for (int page = 1; page <= MAX_JOBS / PAGE_SIZE + 1; page++) {
            var response = apiClient.getRepositoryResource(path + "?per_page=" + PAGE_SIZE + "&page=" + page, token);
            var items = response.path("jobs");
            var count = response.path("total_count");
            if (!items.isArray() || items.size() > PAGE_SIZE || !count.isIntegralNumber()
                || !count.canConvertToLong() || count.asLong() < 0 || count.asLong() > MAX_JOBS
                || (expectedCount != -1 && expectedCount != count.asLong())) {
                throw new IllegalArgumentException("Invalid jobs page.");
            }
            expectedCount = count.asLong();
            for (var item : items) {
                long id = GitHubPayloads.positiveId(item.path("id"));
                if (!ids.add(id) || jobs.size() >= MAX_JOBS
                    || GitHubPayloads.positiveId(item.path("run_id")) != work.runId()
                    || (item.hasNonNull("run_attempt")
                        && GitHubPayloads.positiveId(item.path("run_attempt")) != work.runAttempt())) {
                    throw new IllegalArgumentException("Job identity mismatch.");
                }
                jobs.add(new PipelineDtos.JobData(id, GitHubPayloads.text(item.path("name"), 255),
                    GitHubPayloads.text(item.path("status"), 40), optionalText(item.path("conclusion"), 40),
                    htmlUrl(item.path("html_url"), repositoryName), timestamp(item.path("started_at")),
                    timestamp(item.path("completed_at"))));
            }
            if (items.size() < PAGE_SIZE) {
                if (jobs.size() != expectedCount) {
                    throw new IllegalArgumentException("Incomplete jobs snapshot.");
                }
                return List.copyOf(jobs);
            }
        }
        throw new IllegalArgumentException("Jobs snapshot exceeds the supported size.");
    }

    private String optionalText(JsonNode node, int maxLength) {
        return node.isMissingNode() || node.isNull() ? null : GitHubPayloads.text(node, maxLength);
    }

    private Instant timestamp(JsonNode node) {
        var value = optionalText(node, 40);
        return value == null ? null : Instant.parse(value);
    }

    private String htmlUrl(JsonNode node, String repositoryName) {
        var value = optionalText(node, 2048);
        if (value == null) {
            return null;
        }
        var uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
            || uri.getUserInfo() != null || !uri.getPath().startsWith("/" + repositoryName + "/actions/")) {
            throw new IllegalArgumentException("Invalid workflow URL.");
        }
        return value;
    }
}

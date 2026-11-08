package com.tracepilot.pipeline;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "workflow_runs")
public class WorkflowRun {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;
    @Column(name = "github_run_id", nullable = false)
    private long githubRunId;
    @Column(name = "run_attempt", nullable = false)
    private int runAttempt;
    @Column(name = "github_workflow_id", nullable = false)
    private long githubWorkflowId;
    @Column(name = "workflow_name", nullable = false, length = 255)
    private String workflowName;
    @Column(name = "head_branch", length = 255)
    private String headBranch;
    @Column(name = "head_sha", length = 64)
    private String headSha;
    @Column(nullable = false, length = 40)
    private String status;
    @Column(length = 40)
    private String conclusion;
    @Column(name = "html_url", columnDefinition = "text")
    private String htmlUrl;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "source_updated_at", nullable = false)
    private Instant sourceUpdatedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowRun() {
    }

    public WorkflowRun(UUID repositoryId, PipelineDtos.RunData data, Instant now) {
        this.repositoryId = repositoryId;
        this.githubRunId = data.id();
        this.runAttempt = data.attempt();
        this.createdAt = now;
        update(data, now);
    }

    public boolean accepts(PipelineDtos.RunData data) {
        if (data.sourceUpdatedAt().isBefore(sourceUpdatedAt)) {
            return false;
        }
        // A completed attempt never becomes queued/in-progress again; a rerun has a separate attempt key.
        return !"completed".equals(status) || "completed".equals(data.status());
    }

    public void update(PipelineDtos.RunData data, Instant now) {
        githubWorkflowId = data.workflowId();
        workflowName = data.name();
        headBranch = data.branch();
        headSha = data.headSha();
        status = data.status();
        conclusion = data.conclusion();
        htmlUrl = data.htmlUrl();
        startedAt = data.startedAt();
        completedAt = data.completedAt();
        sourceUpdatedAt = data.sourceUpdatedAt();
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public long getGithubRunId() {
        return githubRunId;
    }

    public int getRunAttempt() {
        return runAttempt;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public String getHeadBranch() {
        return headBranch;
    }

    public String getHeadSha() {
        return headSha;
    }

    public String getStatus() {
        return status;
    }

    public String getConclusion() {
        return conclusion;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}

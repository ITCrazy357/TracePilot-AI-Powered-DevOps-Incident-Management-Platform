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
@Table(name = "workflow_jobs")
public class WorkflowJob {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;
    @Column(name = "github_job_id", nullable = false)
    private long githubJobId;
    @Column(nullable = false, length = 255)
    private String name;
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

    protected WorkflowJob() {
    }

    public WorkflowJob(UUID workflowRunId, PipelineDtos.JobData data) {
        this.workflowRunId = workflowRunId;
        this.githubJobId = data.id();
        update(data);
    }

    public void update(PipelineDtos.JobData data) {
        if ("completed".equals(status) && !"completed".equals(data.status())) {
            return;
        }
        name = data.name();
        status = data.status();
        conclusion = data.conclusion();
        htmlUrl = data.htmlUrl();
        startedAt = data.startedAt();
        completedAt = data.completedAt();
    }

    public UUID getId() {
        return id;
    }

    public long getGithubJobId() {
        return githubJobId;
    }

    public String getName() {
        return name;
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

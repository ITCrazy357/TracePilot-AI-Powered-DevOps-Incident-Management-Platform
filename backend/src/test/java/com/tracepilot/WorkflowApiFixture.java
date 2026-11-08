package com.tracepilot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import tools.jackson.databind.ObjectMapper;

final class WorkflowApiFixture {
    private static final Pattern PATH = Pattern.compile(
        "/repos/acme/repo-0/actions/runs/(\\d+)/attempts/(\\d+)(/jobs)?");
    final AtomicInteger runCalls = new AtomicInteger();
    final AtomicInteger jobCalls = new AtomicInteger();
    volatile int jobCount = 2;
    volatile boolean unavailable;
    volatile boolean partialJobs;
    volatile boolean wrongAttempt;
    volatile boolean pauseJobs;
    volatile boolean failedJob;
    volatile String conclusionOverride;
    volatile String status = "completed";
    volatile String updatedAt = "2026-11-08T01:00:00Z";
    volatile CountDownLatch snapshotRequested = new CountDownLatch(1);
    volatile CountDownLatch releaseSnapshot = new CountDownLatch(1);
    private final ObjectMapper objectMapper = new ObjectMapper();

    void reset() {
        runCalls.set(0);
        jobCalls.set(0);
        jobCount = 2;
        unavailable = false;
        partialJobs = false;
        wrongAttempt = false;
        pauseJobs = false;
        failedJob = false;
        conclusionOverride = null;
        status = "completed";
        updatedAt = "2026-11-08T01:00:00Z";
        snapshotRequested = new CountDownLatch(1);
        releaseSnapshot = new CountDownLatch(1);
    }

    void handle(HttpExchange exchange) throws IOException, InterruptedException {
        var matcher = PATH.matcher(exchange.getRequestURI().getPath());
        if (!matcher.matches()) {
            respond(exchange, 404, Map.of("message", "Unknown workflow fixture route"));
            return;
        }
        long runId = Long.parseLong(matcher.group(1));
        int attempt = Integer.parseInt(matcher.group(2));
        if (matcher.group(3) == null) {
            runCalls.incrementAndGet();
            respond(exchange, unavailable ? 503 : 200, run(runId, attempt));
            return;
        }
        jobCalls.incrementAndGet();
        var body = jobs(runId, attempt, exchange.getRequestURI().getQuery());
        if (pauseJobs) {
            snapshotRequested.countDown();
            releaseSnapshot.await(10, TimeUnit.SECONDS);
        }
        respond(exchange, 200, body);
    }

    private Map<String, Object> run(long runId, int attempt) {
        var data = new HashMap<String, Object>();
        data.put("id", runId);
        data.put("run_attempt", wrongAttempt ? attempt + 1 : attempt);
        data.put("workflow_id", 8000);
        data.put("repository", Map.of("id", 10000));
        data.put("name", "Pipeline CI");
        data.put("head_branch", "main");
        data.put("head_sha", "a".repeat(40));
        data.put("status", status);
        data.put("conclusion", "completed".equals(status)
            ? (conclusionOverride != null ? conclusionOverride : (attempt == 1 ? "failure" : "success")) : null);
        data.put("html_url", "https://github.com/acme/repo-0/actions/runs/" + runId);
        data.put("run_started_at", "2026-11-08T00:00:00Z");
        data.put("updated_at", updatedAt);
        // The real run endpoint does not provide an exact completed_at field.
        return data;
    }

    private Map<String, Object> jobs(long runId, int attempt, String query) {
        int page = Integer.parseInt(query.substring(query.indexOf("&page=") + "&page=".length()));
        var items = new ArrayList<Map<String, Object>>();
        for (int index = (page - 1) * 100; index < Math.min(page * 100, jobCount); index++) {
            var job = new HashMap<String, Object>();
            long id = runId * 10000 + attempt * 1000L + index;
            job.put("id", id);
            job.put("run_id", runId);
            job.put("run_attempt", attempt);
            job.put("name", "job-" + index);
            job.put("status", status);
            job.put("conclusion", "completed".equals(status) ? (failedJob && index == 0 ? "failure" : "success") : null);
            job.put("html_url", "https://github.com/acme/repo-0/actions/runs/" + runId + "/job/" + id);
            job.put("started_at", "2026-11-08T00:00:00Z");
            job.put("completed_at", "completed".equals(status) ? "2026-11-08T00:59:00Z" : null);
            items.add(job);
        }
        return Map.of("total_count", jobCount + (partialJobs ? 1 : 0), "jobs", items);
    }

    private void respond(HttpExchange exchange, int code, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

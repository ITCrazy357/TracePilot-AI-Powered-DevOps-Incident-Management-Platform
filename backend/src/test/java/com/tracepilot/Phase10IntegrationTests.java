package com.tracepilot;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"RATE_LIMIT_ENABLED=false", "RAG_ENABLED=false", "AI_ENABLED=false", "GITHUB_ENABLED=false",
        "PIPELINE_ENABLED=false", "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only",
        "AUTH_SIGNING_SECRET=phase10-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase10IntegrationTests {
    @MockitoSpyBean
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private UUID workspaceId;
    private UUID repositoryId;
    private String token;
    private UUID actorId;

    @BeforeEach
    void scope() throws Exception {
        var email = UUID.randomUUID() + "@example.com";
        var password = "Phase10-fixture-password!";
        assertThat(call("POST", "/api/v1/auth/register", Map.of("email", email, "password", password, "displayName", "Developer")).statusCode()).isEqualTo(201);
        var login = body(call("POST", "/api/v1/auth/login", Map.of("email", email, "password", password)));
        token = login.path("accessToken").asString();
        actorId = UUID.fromString(login.path("user").path("id").asString());
        var workspace = body(call("POST", "/api/v1/workspaces", Map.of("name", "Phase10 fixture", "slug", "fixture-" + UUID.randomUUID())));
        workspaceId = UUID.fromString(workspace.path("id").asString());
        var installationId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO github_installations(id, workspace_id, github_installation_id, account_login, status, created_at, updated_at)
            VALUES (?, ?, ?, 'fixture', 'ACTIVE', now(), now())
            """, installationId, workspaceId, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        jdbc.update("""
            INSERT INTO repositories(id, workspace_id, installation_id, github_repository_id, full_name, is_active, created_at, updated_at)
            VALUES (?, ?, ?, 1, 'fixture/repo', true, now(), now())
            """, repositoryId, workspaceId, installationId);
    }

    @Test
    void listCountAndItemsUseOneSnapshotDuringConcurrentInsert() throws Exception {
        insertDraft("Initial article");
        var counted = new CountDownLatch(1);
        var inserted = new CountDownLatch(1);
        doAnswer(invocation -> {
            var total = invocation.callRealMethod();
            counted.countDown();
            if (!inserted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent insert did not complete.");
            }
            return total;
        }).when(jdbc).queryForObject(eq("SELECT count(*) FROM knowledge_articles WHERE workspace_id = ?"),
            eq(Long.class), eq(workspaceId));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> call("GET", base() + "/knowledge-articles", null));
            try {
                assertThat(counted.await(10, TimeUnit.SECONDS)).isTrue();
                insertDraft("Concurrent article");
            } finally {
                inserted.countDown();
            }
            var response = pending.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            var data = body(response);
            assertThat(data.path("total").asLong()).isEqualTo(1);
            assertThat(data.path("items").size()).isEqualTo(1);
        }
        assertThat(body(call("GET", base() + "/knowledge-articles", null)).path("total").asLong()).isEqualTo(2);
    }

    private void insertDraft(String title) {
        jdbc.update("""
            INSERT INTO knowledge_articles(id, workspace_id, title, root_cause, resolution,
                created_by, created_at, updated_at)
            VALUES (?, ?, ?, 'Reviewed cause', 'Reviewed resolution', ?, now(), now())
            """, UUID.randomUUID(), workspaceId, title, actorId);
    }

    @Test
    void tokenAnalyticsIncludesIndependentEmbeddingsRetriesAndPreservesUnknownMeasurements() throws Exception {
        incident(0, "Token fixture", "OPEN");
        var incidentId = jdbc.queryForObject("SELECT id FROM incidents WHERE workspace_id = ?", UUID.class, workspaceId);
        var analysisId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ai_analyses(id, workspace_id, incident_id, request_id, origin, provider, model,
                prompt_version, input_sha256, status, summary, suspected_root_cause, suggested_fix, completed_at, created_at)
            VALUES (?, ?, ?, ?, 'MANUAL', 'OPENAI', 'chat-fixture', 'fixture', ?, 'SUCCEEDED', 'Summary', 'Cause', 'Fix', now(), now())
            """, analysisId, workspaceId, incidentId, UUID.randomUUID(), "a".repeat(64));
        usage(workspaceId, analysisId, 1, "CHAT", "chat-fixture", "SUCCEEDED", 100L, 50L, "0.0005", Instant.now().minusSeconds(86400));
        usage(workspaceId, analysisId, 2, "CHAT", "unknown-fixture", "UNKNOWN", null, null, null, Instant.now());
        usage(workspaceId, null, 1, "EMBEDDING", "text-embedding-3-small", "SUCCEEDED", 20L, 0L, "0.00001", Instant.now());
        usage(workspaceId, null, 1, "EMBEDDING", "old-fixture", "SUCCEEDED", 900L, 0L, "1", Instant.now().minusSeconds(100 * 86400));
        var other = body(call("POST", "/api/v1/workspaces", Map.of("name", "Foreign fixture", "slug", "other-" + UUID.randomUUID())));
        var otherId = UUID.fromString(other.path("id").asString());
        usage(otherId, null, 1, "EMBEDDING", "foreign-fixture", "SUCCEEDED", 5000L, 0L, "9", Instant.now());
        var response = call("GET", base() + "/ai-usage?days=7", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = body(response);
        assertThat(data.path("daily").size()).isEqualTo(7);
        var totals = data.path("totals");
        assertThat(totals.path("calls").asLong()).isEqualTo(3);
        assertThat(totals.path("inputTokens").asLong()).isEqualTo(120);
        assertThat(totals.path("outputTokens").asLong()).isEqualTo(50);
        assertThat(totals.path("inputSamples").asLong()).isEqualTo(2);
        assertThat(totals.path("outputSamples").asLong()).isEqualTo(2);
        assertThat(totals.path("unknown").asLong()).isEqualTo(1);
        assertThat(totals.path("pricedCalls").asLong()).isEqualTo(2);
        assertThat(totals.path("knownEstimatedCostUsd").asDouble()).isEqualTo(0.00051);
        assertThat(data.path("models").toString()).doesNotContain("old-fixture", "foreign-fixture");
        assertThat(body(call("GET", base() + "/ai-usage?days=7&operation=EMBEDDING", null)).path("totals").path("inputTokens").asLong()).isEqualTo(20);
        var unknown = body(call("GET", base() + "/ai-usage?days=7&model=unknown-fixture", null)).path("totals");
        assertThat(unknown.path("inputTokens").isNull()).isTrue();
        assertThat(unknown.path("outputTokens").isNull()).isTrue();
        assertThat(unknown.path("knownEstimatedCostUsd").isNull()).isTrue();
        assertThat(call("GET", base() + "/ai-usage?operation=INVALID", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", base() + "/ai-usage?days=91", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/workspaces/" + UUID.randomUUID() + "/ai-usage", null).statusCode()).isEqualTo(404);
    }

    @Test
    void emptyTokenAnalyticsHasZeroCallsAndCompleteCalendarWithoutInventingUsage() throws Exception {
        var response = call("GET", base() + "/ai-usage?days=7", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = body(response);
        assertThat(data.path("totals").path("calls").asLong()).isZero();
        assertThat(data.path("daily").size()).isEqualTo(7);
        assertThat(data.path("byModel")).isEmpty();
    }

    private void usage(UUID scope, UUID analysisId, int attempt, String operation, String model, String status,
                       Long input, Long output, String cost, Instant createdAt) {
        jdbc.update("""
            INSERT INTO ai_usage_events(id, workspace_id, analysis_id, attempt, operation, provider, model,
                status, input_tokens, output_tokens, estimated_cost_usd, created_at)
            VALUES (?, ?, ?, ?, ?, 'OPENAI', ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), scope, analysisId, attempt, operation, model, status, input, output,
            cost == null ? null : new BigDecimal(cost), Timestamp.from(createdAt));
    }

    @Test
    void dashboardAggregatesAllRowsScopesWorkspaceAndFillsEmptyUtcDays() throws Exception {
        incident(0, "Open incident", "OPEN");
        incident(1, "Resolved incident", "RESOLVED");
        jdbc.update("UPDATE workflow_runs SET conclusion = 'success' WHERE repository_id = ? AND github_run_id = 2", repositoryId);
        var response = call("GET", base() + "/dashboard?days=7", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = body(response);
        assertThat(data.path("daily").size()).isEqualTo(7);
        assertThat(data.path("timeZone").asString()).isEqualTo("UTC");
        long incidents = 0;
        long successes = 0;
        long failures = 0;
        for (var day : data.path("daily")) {
            incidents += day.path("incidents").asLong();
            successes += day.path("successfulRuns").asLong();
            failures += day.path("failedRuns").asLong();
        }
        assertThat(incidents).isEqualTo(2);
        assertThat(successes).isEqualTo(1);
        assertThat(failures).isEqualTo(1);
        assertThat(data.path("statuses").size()).isEqualTo(2);
        assertThat(call("GET", base() + "/dashboard?days=91", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/workspaces/" + UUID.randomUUID() + "/dashboard", null).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE workspace_members SET status = 'REMOVED' WHERE workspace_id = ? AND user_id = ?", workspaceId, actorId);
        assertThat(call("GET", base() + "/dashboard", null).statusCode()).isEqualTo(404);
    }

    @Test
    void ordinaryKnowledgeSearchWorksWithoutAiAndTreatsWildcardsLiterally() throws Exception {
        assertThat(call("POST", base() + "/knowledge-articles", Map.of("title", "Socket fix", "rootCause", "100% occupied", "resolution", "Inspect listeners")).statusCode()).isEqualTo(201);
        assertThat(call("POST", base() + "/knowledge-articles", Map.of("title", "Heap fix", "rootCause", "Memory pressure", "resolution", "Inspect allocations")).statusCode()).isEqualTo(201);
        var result = call("GET", base() + "/knowledge-articles?q=%25&status=DRAFT", null);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(body(result).path("total").asLong()).isEqualTo(1);
        assertThat(body(result).path("items").get(0).path("title").asString()).isEqualTo("Socket fix");
        assertThat(body(call("GET", base() + "/knowledge-articles?q=ALLOCATIONS&status=DRAFT", null)).path("total").asLong()).isEqualTo(1);
        assertThat(body(call("GET", base() + "/knowledge-articles?status=APPROVED", null)).path("total").asLong()).isZero();
        assertThat(call("GET", base() + "/knowledge-articles?status=INVALID", null).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage_events WHERE workspace_id = ?", Long.class, workspaceId)).isZero();
    }

    @Test
    void criteriaFilterTheWholeWorkspaceBeforeCountingAndPagination() throws Exception {
        for (int index = 0; index < 23; index++) {
            incident(index, index == 22 ? "Target 100% regression" : "Other incident", index == 22 ? "RESOLVED" : "OPEN");
        }
        var response = call("GET", base() + "/incidents?status=RESOLVED&size=20", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("total").asLong()).isEqualTo(1);
        assertThat(body(response).path("items").get(0).path("title").asString()).isEqualTo("Target 100% regression");
        var criteria = "/incidents?q=%25&repositoryId=" + repositoryId + "&assignedTo=" + actorId + "&severity=MEDIUM";
        assertThat(body(call("GET", base() + criteria, null)).path("total").asLong()).isEqualTo(1);
        assertThat(body(call("GET", base() + criteria + "&page=1&size=1", null)).path("items")).isEmpty();
        assertThat(body(call("GET", base() + "/incidents?repositoryId=" + UUID.randomUUID(), null)).path("total").asLong()).isZero();
        assertThat(call("GET", base() + "/incidents?status=INVALID", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", base() + "/incidents?severity=INVALID", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/workspaces/" + UUID.randomUUID() + "/incidents?q=Target", null).statusCode()).isEqualTo(404);
    }

    private void incident(int sequence, String title, String status) {
        var runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workflow_runs(id, repository_id, github_run_id, run_attempt, github_workflow_id, workflow_name, status, conclusion,
                source_updated_at, created_at, updated_at)
            VALUES (?, ?, ?, 1, 1, 'Fixture', 'completed', 'failure', now(), now(), now())
            """, runId, repositoryId, sequence + 1);
        jdbc.update("""
            INSERT INTO incidents(id, workspace_id, repository_id, workflow_run_id, title, status, assigned_to,
                detected_at, resolved_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now() - (? * interval '1 second'),
                CASE WHEN ? = 'RESOLVED' THEN now() ELSE NULL END, now(), now())
            """, UUID.randomUUID(), workspaceId, repositoryId, runId, title, status, actorId, sequence, status);
    }

    private String base() {
        return "/api/v1/workspaces/" + workspaceId;
    }

    private HttpResponse<String> call(String method, String path, Object value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var publisher = value == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(value));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode body(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }
}

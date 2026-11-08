package com.tracepilot;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.tracepilot.quality.QualityRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"RAG_ENABLED=false", "AI_ENABLED=false", "GITHUB_ENABLED=false", "PIPELINE_ENABLED=false",
        "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only",
        "AUTH_SIGNING_SECRET=phase07-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase07IntegrationTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    QualityRepository quality;
    @Value("${local.server.port}")
    int port;
    private Account owner;
    private Account member;
    private Account outsider;
    private UUID workspaceId;
    private UUID incidentId;
    private UUID analysisId;

    @BeforeAll
    void accounts() throws Exception {
        owner = account();
        member = account();
        outsider = account();
    }

    @BeforeEach
    void scope() {
        workspaceId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var repositoryId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        incidentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workspaces (id, name, slug, created_by, created_at, updated_at)
            VALUES (?, 'Quality fixture', ?, ?, now(), now())
            """, workspaceId, workspaceId.toString(), owner.id());
        membership(owner, "OWNER");
        membership(member, "MEMBER");
        jdbc.update("""
            INSERT INTO github_installations
                (id, workspace_id, github_installation_id, account_login, status, created_at, updated_at)
            VALUES (?, ?, ?, 'fixture', 'ACTIVE', now(), now())
            """, installationId, workspaceId, positiveId());
        jdbc.update("""
            INSERT INTO repositories (id, workspace_id, installation_id, github_repository_id,
                full_name, is_active, created_at, updated_at)
            VALUES (?, ?, ?, 1, 'fixture/repo', true, now(), now())
            """, repositoryId, workspaceId, installationId);
        jdbc.update("""
            INSERT INTO workflow_runs (id, repository_id, github_run_id, run_attempt, github_workflow_id,
                workflow_name, status, conclusion, source_updated_at, created_at, updated_at)
            VALUES (?, ?, 1, 1, 1, 'Fixture', 'completed', 'failure', now(), now(), now())
            """, runId, repositoryId);
        jdbc.update("""
            INSERT INTO incidents (id, workspace_id, repository_id, workflow_run_id, title,
                detected_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'Failure', now(), now(), now())
            """, incidentId, workspaceId, repositoryId, runId);
        analysisId = analysis("SUCCEEDED", Instant.now().minusSeconds(86400), 1000);
    }

    @Test
    void memberCanCreateReadReviseAndReplayTheirOwnRedactedFeedback() throws Exception {
        assertThat(body(call("GET", feedbackPath(), member, null)).path("feedback").isNull()).isTrue();
        var request = vote(true, "ACTIONABLE_FIX", "token=do-not-store-this-secret", 0);
        var response = call("PUT", feedbackPath(), member, request);
        assertThat(response.statusCode()).isEqualTo(200);
        var saved = body(response);
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("comment").asString()).contains("[REDACTED]").doesNotContain("do-not-store");
        assertThat(body(call("PUT", feedbackPath(), member, request)).path("version").asLong()).isEqualTo(1);
        assertThat(body(call("GET", feedbackPath(), owner, null)).path("feedback").isNull()).isTrue();
        var revised = call("PUT", feedbackPath(), member, vote(false, "INCORRECT_DIAGNOSIS", "Wrong cause", 1));
        assertThat(revised.statusCode()).isEqualTo(200);
        assertThat(body(revised).path("helpful").asBoolean()).isFalse();
        assertThat(body(revised).path("version").asLong()).isEqualTo(2);
        assertThat(body(revised).path("createdAt").asString()).isEqualTo(saved.path("createdAt").asString());
        assertThat(countVotes()).isEqualTo(1);
    }

    @Test
    void rejectsStaleVersionsWithoutOverwritingTheNewerVote() throws Exception {
        assertThat(call("PUT", feedbackPath(), member, vote(true, "OTHER", "", 2)).statusCode()).isEqualTo(409);
        call("PUT", feedbackPath(), member, vote(true, "OTHER", "", 0));
        assertThat(call("PUT", feedbackPath(), member, vote(false, "OTHER", "stale", 0)).statusCode()).isEqualTo(409);
        assertThat(body(call("GET", feedbackPath(), member, null)).path("feedback").path("helpful").asBoolean()).isTrue();
    }

    @Test
    void concurrentConflictingFirstVotesProduceOneRecordAndOneConflict() {
        var path = feedbackPath();
        var first = CompletableFuture.supplyAsync(() -> uncheckedPut(path, vote(true, "USEFUL_EVIDENCE", "", 0)));
        var second = CompletableFuture.supplyAsync(() -> uncheckedPut(path, vote(false, "INSUFFICIENT_EVIDENCE", "", 0)));
        assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(200, 409);
        assertThat(countVotes()).isEqualTo(1);
    }

    @Test
    void parallelUsersEachRetainTheirOwnVote() {
        var path = feedbackPath();
        var first = CompletableFuture.supplyAsync(() -> uncheckedPut(path, vote(true, "OTHER", "", 0)));
        var second = CompletableFuture.supplyAsync(() -> {
            try {
                return call("PUT", path, owner, vote(false, "OTHER", "", 0)).statusCode();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThat(List.of(first.join(), second.join())).containsOnly(200);
        assertThat(countVotes()).isEqualTo(2);
    }

    @Test
    void validationRequiresAnExplicitVoteCompatibleReasonAndBoundedComment() throws Exception {
        for (var invalid : List.of(
            Map.of("reason", "OTHER", "version", 0),
            Map.of("helpful", true, "version", 0),
            vote(false, "ACTIONABLE_FIX", "", 0),
            vote(true, "NOT_A_REAL_INCIDENT", "", 0),
            vote(true, "UNKNOWN_REASON", "", 0),
            vote(true, "OTHER", "a".repeat(2001), 0),
            vote(true, "OTHER", "", -1))) {
            assertThat(call("PUT", feedbackPath(), member, invalid).statusCode()).isEqualTo(400);
        }
        assertThat(countVotes()).isZero();
    }

    @Test
    void onlySucceededReportsAcceptFeedbackAndFalsePositiveVoteDoesNotChangeIncident() throws Exception {
        for (var status : List.of("FAILED", "PENDING", "RUNNING")) {
            jdbc.update("UPDATE ai_analyses SET status = ?, completed_at = ? WHERE id = ?", status,
                status.equals("FAILED") ? Timestamp.from(Instant.now()) : null, analysisId);
            assertThat(call("PUT", feedbackPath(), member, vote(false, "OTHER", "", 0)).statusCode()).isEqualTo(409);
        }
        jdbc.update("UPDATE ai_analyses SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?", analysisId);
        assertThat(call("PUT", feedbackPath(), member, vote(false, "NOT_A_REAL_INCIDENT", "", 0)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM incidents WHERE id = ?", String.class, incidentId)).isEqualTo("OPEN");
    }

    @Test
    void hidesFeedbackAndMetricsFromOutsidersRemovedMembersAndWrongIncidentPaths() throws Exception {
        assertThat(call("GET", feedbackPath(), outsider, null).statusCode()).isEqualTo(404);
        assertThat(call("PUT", feedbackPath(), outsider, vote(true, "OTHER", "", 0)).statusCode()).isEqualTo(404);
        assertThat(call("GET", base() + "/quality", outsider, null).statusCode()).isEqualTo(404);
        var wrongIncident = feedbackPath().replace(incidentId.toString(), UUID.randomUUID().toString());
        assertThat(call("PUT", wrongIncident, member, vote(true, "OTHER", "", 0)).statusCode()).isEqualTo(404);
        var wrongWorkspace = feedbackPath().replace(workspaceId.toString(), UUID.randomUUID().toString());
        assertThat(call("GET", wrongWorkspace, member, null).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE workspace_members SET status = 'REMOVED' WHERE workspace_id = ? AND user_id = ?",
            workspaceId, member.id());
        assertThat(call("PUT", feedbackPath(), member, vote(true, "OTHER", "", 0)).statusCode()).isEqualTo(404);
        assertThat(call("GET", base() + "/quality", member, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", base() + "/quality", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void aggregatesTerminalSuccessSeparatelyFromHumanFeedbackWithoutRetryVoteFanout() throws Exception {
        var created = Instant.now().minusSeconds(86400);
        analysis("SUCCEEDED", created, 3000);
        analysis("FAILED", created, 5000);
        analysis("PENDING", created, 0);
        usage(analysisId, 1, "CHAT", "SUCCEEDED", 100L, 20L, "0.01", 400L, created);
        usage(analysisId, 2, "CHAT", "UNKNOWN", null, null, null, 500L, created);
        call("PUT", feedbackPath(), member, vote(true, "ACTIONABLE_FIX", "", 0));
        call("PUT", feedbackPath(), owner, vote(false, "INCORRECT_DIAGNOSIS", "", 0));
        var response = call("GET", base() + "/quality?days=7", member, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var result = body(response);
        var analyses = result.path("analyses");
        assertThat(analyses.path("total").asLong()).isEqualTo(4);
        assertThat(analyses.path("succeeded").asLong()).isEqualTo(2);
        assertThat(analyses.path("failed").asLong()).isEqualTo(1);
        assertThat(analyses.path("successRate").asDouble()).isCloseTo(2.0 / 3, within(0.000001));
        assertThat(analyses.path("averageLatencyMs").asDouble()).isCloseTo(3000, within(0.01));
        assertThat(analyses.path("p95LatencyMs").asDouble()).isCloseTo(4800, within(0.01));
        var feedback = result.path("feedback");
        assertThat(feedback.path("votes").asLong()).isEqualTo(2);
        assertThat(feedback.path("reviewedAnalyses").asLong()).isEqualTo(1);
        assertThat(feedback.path("helpfulRate").asDouble()).isEqualTo(0.5);
        assertThat(feedback.path("reviewCoverage").asDouble()).isEqualTo(0.5);
        assertThat(result.path("reasons").size()).isEqualTo(2);
        assertThat(result.path("usage").get(0).path("calls").asLong()).isEqualTo(2);
        assertThat(result.path("errors").get(0).path("code").asString()).isEqualTo("AI_INVALID_RESPONSE");
        assertThat(Duration.between(Instant.parse(result.path("from").asString()),
            Instant.parse(result.path("to").asString()))).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void usageIncludesIndependentEmbeddingsWithUnknownCostAndTokenCoverage() throws Exception {
        var time = Instant.now().minusSeconds(10);
        usage(analysisId, 1, "CHAT", "SUCCEEDED", 100L, 20L, "0.01", 500L, time);
        usage(analysisId, 2, "CHAT", "UNKNOWN", null, null, null, null, time);
        usage(null, 1, "EMBEDDING", "SUCCEEDED", 30L, null, "0.001", 50L, time);
        usage(null, 1, "EMBEDDING", "FAILED", null, null, null, 20L, time);
        var groups = body(call("GET", base() + "/quality", owner, null)).path("usage");
        assertThat(groups.size()).isEqualTo(2);
        var chat = groups.get(0);
        assertThat(chat.path("operation").asString()).isEqualTo("CHAT");
        assertThat(chat.path("calls").asLong()).isEqualTo(2);
        assertThat(chat.path("unknown").asLong()).isEqualTo(1);
        assertThat(chat.path("inputTokens").asLong()).isEqualTo(100);
        assertThat(chat.path("inputTokenSamples").asLong()).isEqualTo(1);
        assertThat(chat.path("outputTokenSamples").asLong()).isEqualTo(1);
        assertThat(chat.path("knownEstimatedCostUsd").asDouble()).isCloseTo(0.01, within(0.000001));
        assertThat(chat.path("pricedCalls").asLong()).isEqualTo(1);
        assertThat(chat.path("durationSamples").asLong()).isEqualTo(1);
        assertThat(groups.get(1).path("inputTokens").asLong()).isEqualTo(30);
        assertThat(groups.get(1).path("outputTokens").isNull()).isTrue();
        assertThat(groups.get(1).path("outputTokenSamples").asLong()).isZero();
        assertThat(groups.get(1).path("failed").asLong()).isEqualTo(1);
    }

    @Test
    void noSamplesStayNullAndTimeWindowValidationIsBounded() throws Exception {
        jdbc.update("DELETE FROM ai_analyses WHERE id = ?", analysisId);
        var dashboard = body(call("GET", base() + "/quality", owner, null));
        assertThat(dashboard.path("analyses").path("successRate").isNull()).isTrue();
        assertThat(dashboard.path("analyses").path("averageLatencyMs").isNull()).isTrue();
        assertThat(dashboard.path("feedback").path("helpfulRate").isNull()).isTrue();
        assertThat(dashboard.path("feedback").path("reviewCoverage").isNull()).isTrue();
        assertThat(dashboard.path("usage").isEmpty()).isTrue();
        for (var days : List.of("0", "91", "-1", "abc")) {
            assertThat(call("GET", base() + "/quality?days=" + days, owner, null).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void rangeIsInclusiveAtStartExclusiveAtEndAndDoesNotReadAnotherWorkspace() {
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = from.plusSeconds(86400);
        analysis("SUCCEEDED", from, 1000);
        analysis("FAILED", from.minusNanos(1000), 1000);
        analysis("FAILED", to, 1000);
        usage(analysisId, 1, "CHAT", "SUCCEEDED", 10L, 1L, "0.01", 1L, from);
        usage(analysisId, 2, "CHAT", "FAILED", 20L, 2L, "0.02", 2L, to);
        assertThat(quality.analyses(workspaceId, from, to).total()).isEqualTo(1);
        assertThat(quality.analyses(UUID.randomUUID(), from, to).total()).isZero();
        assertThat(quality.usage(workspaceId, from, to).getFirst().calls()).isEqualTo(1);
        assertThat(quality.usage(UUID.randomUUID(), from, to)).isEmpty();
    }

    @Test
    void latestFeedbackRemainsInItsAnalysisCohortRatherThanBecomingANewVote() throws Exception {
        call("PUT", feedbackPath(), member, vote(true, "OTHER", "", 0));
        call("PUT", feedbackPath(), member, vote(false, "NOT_A_REAL_INCIDENT", "", 1));
        var dashboard = body(call("GET", base() + "/quality", member, null));
        assertThat(dashboard.path("feedback").path("votes").asLong()).isEqualTo(1);
        assertThat(dashboard.path("feedback").path("helpful").asLong()).isZero();
        assertThat(dashboard.path("reasons").get(0).path("reason").asString()).isEqualTo("NOT_A_REAL_INCIDENT");
        jdbc.update("UPDATE ai_analyses SET created_at = now() - interval '91 days' WHERE id = ?", analysisId);
        assertThat(body(call("GET", base() + "/quality?days=90", member, null))
            .path("feedback").path("votes").asLong()).isZero();
    }

    private UUID analysis(String status, Instant created, long latency) {
        var id = UUID.randomUUID();
        boolean terminal = List.of("SUCCEEDED", "FAILED").contains(status);
        jdbc.update("""
            INSERT INTO ai_analyses (id, workspace_id, incident_id, request_id, origin, provider, model,
                prompt_version, status, input_sha256, summary, suspected_root_cause, suggested_fix,
                error_code, started_at, completed_at, created_at)
            VALUES (?, ?, ?, ?, 'MANUAL', 'openai', 'fixture', 'incident-rca-v2', ?, ?, 'Summary', 'Cause', 'Fix',
                ?, ?, ?, ?)
            """, id, workspaceId, incidentId, UUID.randomUUID(), status, "a".repeat(64),
            status.equals("FAILED") ? "AI_INVALID_RESPONSE" : null, Timestamp.from(created),
            terminal ? Timestamp.from(created.plusMillis(latency)) : null, Timestamp.from(created));
        return id;
    }

    private void usage(UUID analysis, int attempt, String operation, String status, Long input, Long output,
                       String cost, Long duration, Instant created) {
        jdbc.update("""
            INSERT INTO ai_usage_events (id, workspace_id, analysis_id, attempt, operation, provider, model,
                status, input_tokens, output_tokens, estimated_cost_usd, duration_ms, created_at)
            VALUES (?, ?, ?, ?, ?, 'openai', ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), workspaceId, analysis, attempt, operation,
            operation.equals("CHAT") ? "fixture" : "text-embedding-3-small", status, input, output,
            cost == null ? null : new BigDecimal(cost), duration, Timestamp.from(created));
    }

    private void membership(Account account, String role) {
        jdbc.update("""
            INSERT INTO workspace_members (id, workspace_id, user_id, role, status, joined_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', now())
            """, UUID.randomUUID(), workspaceId, account.id(), role);
    }

    private Account account() throws Exception {
        var email = UUID.randomUUID() + "@example.com";
        var password = "Phase07-fixture-password!";
        assertThat(call("POST", "/api/v1/auth/register", null,
            Map.of("email", email, "password", password, "displayName", "Quality developer")).statusCode()).isEqualTo(201);
        var response = call("POST", "/api/v1/auth/login", null, Map.of("email", email, "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        var data = body(response);
        return new Account(UUID.fromString(data.path("user").path("id").asString()), data.path("accessToken").asString());
    }

    private long positiveId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }

    private Map<String, Object> vote(boolean helpful, String reason, String comment, long version) {
        return Map.of("helpful", helpful, "reason", reason, "comment", comment, "version", version);
    }

    private int uncheckedPut(String path, Object value) {
        try {
            return call("PUT", path, member, value).statusCode();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long countVotes() {
        return jdbc.queryForObject("SELECT count(*) FROM incident_feedback WHERE workspace_id = ?", Long.class, workspaceId);
    }

    private String base() {
        return "/api/v1/workspaces/" + workspaceId;
    }

    private String feedbackPath() {
        return base() + "/incidents/" + incidentId + "/analyses/" + analysisId + "/feedback";
    }

    private HttpResponse<String> call(String method, String path, Account actor, Object value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (actor != null) {
            request.header("Authorization", "Bearer " + actor.token());
        }
        var publisher = value == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(value));
        return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private record Account(UUID id, String token) {
    }
}

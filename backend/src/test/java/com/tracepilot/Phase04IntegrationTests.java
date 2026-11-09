package com.tracepilot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.tracepilot.identity.TokenService;
import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineQueues;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"RAG_ENABLED=false", "AI_ENABLED=false", "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only",
        "AUTH_SIGNING_SECRET=phase04-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase04IntegrationTests {
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID OTHER_WORKSPACE_ID = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();
    private static final UUID REPOSITORY_ID = UUID.randomUUID();
    private static final GitHubApiFixture GITHUB = new GitHubApiFixture();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    AmqpAdmin rabbitAdmin;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    JwtEncoder jwtEncoder;
    @Value("${local.server.port}")
    int port;
    private Account owner;
    private Account member;
    private Account outsider;

    @DynamicPropertySource
    static void pipelineProperties(DynamicPropertyRegistry registry) {
        registry.add("tracepilot.github.enabled", () -> true);
        registry.add("tracepilot.github.app-id", () -> GitHubApiFixture.APP_ID);
        registry.add("tracepilot.github.installation-id", () -> GitHubApiFixture.INSTALLATION_ID);
        registry.add("tracepilot.github.workspace-id", WORKSPACE_ID::toString);
        registry.add("tracepilot.github.private-key-path", () -> GITHUB.keyPath.toString());
        registry.add("tracepilot.github.webhook-secret", () -> "phase04-fixture-webhook-secret-not-for-production");
        registry.add("tracepilot.github.api-base-url", GITHUB::baseUrl);
        registry.add("tracepilot.pipeline.enabled", () -> true);
        registry.add("tracepilot.pipeline.retry-delay", () -> "100ms");
        registry.add("tracepilot.pipeline.dispatch-delay-ms", () -> 100);
        registry.add("tracepilot.notifications.poll-delay-ms", () -> 100);
    }

    @BeforeAll
    void createAccounts() throws Exception {
        owner = createAccount();
        member = createAccount();
        outsider = createAccount();
    }

    @BeforeEach
    void prepareScope() {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbcTemplate.queryForObject("""
            SELECT count(*) FROM outbox_events WHERE processing_status IN ('PENDING', 'RUNNING', 'RETRY')
                OR status = 'PUBLISHING' OR (processing_status = 'FAILED' AND status <> 'PUBLISHED')
            """, Integer.class) == 0);
        for (var queue : List.of(PipelineQueues.SYNC_QUEUE, PipelineQueues.LOGS_QUEUE, PipelineQueues.DEAD_QUEUE)) {
            rabbitAdmin.purgeQueue(queue, false);
        }
        GITHUB.reset();
        GITHUB.workflows.failedJob = true;
        for (var table : List.of("outbox_events", "analysis_evidence", "ai_usage_events", "ai_analyses",
            "knowledge_chunks", "knowledge_articles", "notifications", "incident_log_snippets", "incidents",
            "workflow_jobs", "workflow_runs", "webhook_deliveries", "repositories", "github_installations")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id IN (?, ?)", WORKSPACE_ID, OTHER_WORKSPACE_ID);
        jdbcTemplate.update("DELETE FROM workspaces WHERE id IN (?, ?)", WORKSPACE_ID, OTHER_WORKSPACE_ID);
        insertWorkspace(WORKSPACE_ID, owner);
        insertWorkspace(OTHER_WORKSPACE_ID, outsider);
        insertMember(WORKSPACE_ID, owner, "OWNER");
        insertMember(WORKSPACE_ID, member, "MEMBER");
        insertMember(OTHER_WORKSPACE_ID, outsider, "OWNER");
        jdbcTemplate.update("""
            INSERT INTO github_installations (id, workspace_id, github_installation_id, account_login,
                status, created_at, updated_at, version) VALUES (?, ?, 501, 'acme', 'ACTIVE', now(), now(), 0)
            """, INSTALLATION_ID, WORKSPACE_ID);
        jdbcTemplate.update("""
            INSERT INTO repositories (id, workspace_id, installation_id, github_repository_id,
                full_name, default_branch, is_active, created_at, updated_at)
            VALUES (?, ?, ?, 10000, 'acme/repo-0', 'main', true, now(), now())
            """, REPOSITORY_ID, WORKSPACE_ID, INSTALLATION_ID);
    }

    @AfterAll
    void closeFixtures() throws Exception {
        GITHUB.logs.release.countDown();
        httpClient.close();
        GITHUB.close();
    }

    @Test
    void failedAttemptCreatesOneIncidentAndOnlyRedactedSnippetsDespiteDuplicateWork() throws Exception {
        var syncId = requestSync(1);
        waitFor(syncId, "SUCCEEDED");
        var incidentId = incidentId();
        waitFor(logEventId(), "SUCCEEDED");
        var original = getIncident(incidentId);
        var logs = call("GET", incidentPath(incidentId) + "/logs", member.accessToken(), null);
        assertThat(logs.statusCode()).isEqualTo(200);
        assertThat(logs.body()).contains("READY", "expected HTTP 200", "[REDACTED]")
            .doesNotContain("private-header-value", "database-private-value", "private-url-value",
                GitHubApiFixture.INSTALLATION_TOKEN, "private-signed-query");
        assertThat(GITHUB.logs.authorizationForwarded).isFalse();
        var safe = jdbcTemplate.queryForObject("SELECT content FROM incident_log_snippets", String.class);
        assertThat(jdbcTemplate.queryForObject("SELECT content_sha256 FROM incident_log_snippets", String.class))
            .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(safe.getBytes(StandardCharsets.UTF_8))));
        publish(PipelineQueues.SYNC_KEY, syncId);
        publish(PipelineQueues.LOGS_KEY, logEventId());
        waitFor(requestSync(1), "SUCCEEDED");
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("incident_log_snippets")).isEqualTo(1);
        assertThat(GITHUB.logs.calls).hasValue(1);
        assertThat(getIncident(incidentId).path("id").asString()).isEqualTo(original.path("id").asString());
        assertThat(count("notifications")).isEqualTo(4);
    }

    @Test
    void rerunsUseDifferentIncidentsAndCancelledOrSuccessfulAttemptsDoNotOpenIncidents() throws Exception {
        waitFor(requestSync(1), "SUCCEEDED");
        GITHUB.workflows.conclusionOverride = "failure";
        waitFor(requestSync(2), "SUCCEEDED");
        assertThat(count("incidents")).isEqualTo(2);
        GITHUB.workflows.conclusionOverride = "success";
        waitFor(requestSync(3), "SUCCEEDED");
        GITHUB.workflows.conclusionOverride = "cancelled";
        waitFor(requestSync(4), "SUCCEEDED");
        assertThat(count("incidents")).isEqualTo(2);
    }

    @Test
    void assignmentStatusLifecycleAndVersionsRespectWorkspacePermissions() throws Exception {
        waitFor(requestSync(1), "SUCCEEDED");
        var id = incidentId();
        assertThat(changeStatus(member, id, "INVESTIGATING", 0).statusCode()).isEqualTo(403);
        assertThat(changeStatus(owner, id, "RESOLVED", 0).statusCode()).isEqualTo(409);
        assertThat(assign(owner, id, outsider.id(), 0).statusCode()).isEqualTo(404);
        assertThat(assign(member, id, member.id(), 0).statusCode()).isEqualTo(403);
        assertThat(assign(owner, id, member.id(), 0).statusCode()).isEqualTo(200);
        assertThat(changeStatus(owner, id, "INVESTIGATING", 0).statusCode()).isEqualTo(409);
        assertThat(changeStatus(member, id, "INVESTIGATING", 1).statusCode()).isEqualTo(200);
        assertThat(changeStatus(member, id, "RESOLVED", 2).statusCode()).isEqualTo(200);
        assertThat(getIncident(id).path("resolvedAt").isNull()).isFalse();
        assertThat(changeStatus(member, id, "INVESTIGATING", 3).statusCode()).isEqualTo(200);
        assertThat(getIncident(id).path("resolvedAt").isNull()).isTrue();
        long before = count("notifications");
        assertThat(changeStatus(member, id, "INVESTIGATING", 4).statusCode()).isEqualTo(200);
        assertThat(getIncident(id).path("version").asLong()).isEqualTo(4);
        assertThat(count("notifications")).isEqualTo(before);
        assertThat(call("PATCH", incidentPath(id) + "/status", owner.accessToken(),
            Map.of("status", "RESOLVED")).statusCode()).isEqualTo(400);
    }

    @Test
    void concurrentEditsAllowOneWinnerAndRejectTheStaleVersion() throws Exception {
        waitFor(requestSync(1), "SUCCEEDED");
        var id = incidentId();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return changeStatus(owner, id, "INVESTIGATING", 0).statusCode();
            });
            var second = executor.submit(() -> {
                start.await();
                return changeStatus(owner, id, "IGNORED", 0).statusCode();
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(getIncident(id).path("version").asLong()).isEqualTo(1);
    }

    @Test
    void allReadsAndNotificationUpdatesAreScopedToTheCurrentMember() throws Exception {
        waitFor(requestSync(1), "SUCCEEDED");
        var id = incidentId();
        for (var suffix : List.of("", "/" + id, "/" + id + "/logs")) {
            assertThat(call("GET", basePath() + "/incidents" + suffix, outsider.accessToken(), null).statusCode()).isEqualTo(404);
        }
        assertThat(call("GET", basePath() + "/incidents", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", basePath() + "/incidents?size=101", owner.accessToken(), null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/workspaces/" + OTHER_WORKSPACE_ID + "/incidents/" + id,
            outsider.accessToken(), null).statusCode()).isEqualTo(404);
        var notificationId = jdbcTemplate.queryForObject(
            "SELECT id FROM notifications WHERE user_id = ? ORDER BY sequence_number LIMIT 1", UUID.class, owner.id());
        var readPath = basePath() + "/notifications/" + notificationId + "/read";
        assertThat(call("PATCH", readPath, member.accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(call("PATCH", readPath, owner.accessToken(), null).statusCode()).isEqualTo(204);
        assertThat(call("GET", basePath() + "/notifications", owner.accessToken(), null).body()).contains("readAt");
        jdbcTemplate.update("UPDATE workspace_members SET status = 'REMOVED' WHERE workspace_id = ? AND user_id = ?",
            WORKSPACE_ID, member.id());
        assertThat(call("GET", incidentPath(id) + "/logs", member.accessToken(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void providerFailuresRetryIntoDlqWithoutLosingTheIncidentOrPersistingTheErrorBody() throws Exception {
        GITHUB.logs.unavailable = true;
        waitFor(requestSync(1), "SUCCEEDED");
        var eventId = logEventId();
        waitFor(eventId, "FAILED");
        await().atMost(Duration.ofSeconds(10)).until(() -> "PUBLISHED".equals(outboxRepository.find(eventId).publicationStatus()));
        var dead = rabbitTemplate.receive(PipelineQueues.DEAD_QUEUE, 3000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
        assertThat(GITHUB.logs.calls).hasValue(3);
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("incident_log_snippets")).isZero();
        var logs = call("GET", incidentPath(incidentId()) + "/logs", owner.accessToken(), null);
        assertThat(logs.body()).contains("FAILED", "GITHUB_LOG_CAPTURE_FAILED").doesNotContain("provider-error-secret");
        assertThat(jdbcTemplate.queryForObject("SELECT payload::text FROM outbox_events WHERE id = ?", String.class, eventId))
            .doesNotContain("provider-error-secret", "Authorization");
    }

    @Test
    void unsafeRedirectAndOversizedLogNeverPersistRawContent() throws Exception {
        GITHUB.logs.redirectOverride = "http://169.254.169.254/latest/meta-data";
        waitFor(requestSync(1), "SUCCEEDED");
        waitFor(logEventId(), "FAILED");
        assertThat(count("incident_log_snippets")).isZero();
        GITHUB.logs.redirectOverride = null;
        GITHUB.logs.oversized = true;
        GITHUB.workflows.conclusionOverride = "failure";
        waitFor(requestSync(2), "SUCCEEDED");
        var secondEvent = jdbcTemplate.queryForObject("""
            SELECT o.id FROM outbox_events o JOIN incidents i ON i.id::text = o.payload->>'incidentId'
            JOIN workflow_runs r ON r.id = i.workflow_run_id WHERE r.run_attempt = 2
            """, UUID.class);
        waitFor(secondEvent, "FAILED");
        assertThat(count("incident_log_snippets")).isZero();
    }

    @Test
    void revocationDuringDownloadPreventsStoringLogs() throws Exception {
        GITHUB.logs.pause = true;
        waitFor(requestSync(1), "SUCCEEDED");
        try {
            assertThat(GITHUB.logs.requested.await(5, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("UPDATE github_installations SET status = 'SUSPENDED', version = version + 1 WHERE id = ?",
                INSTALLATION_ID);
        } finally {
            GITHUB.logs.release.countDown();
        }
        waitFor(logEventId(), "IGNORED");
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("incident_log_snippets")).isZero();
    }

    @Test
    void aBodyThatStallsAfterHeadersTimesOutAndReachesTheBoundedRetryLimit() throws Exception {
        GITHUB.logs.pause = true;
        try {
            waitFor(requestSync(1), "SUCCEEDED");
            assertThat(GITHUB.logs.requested.await(5, TimeUnit.SECONDS)).isTrue();
            var started = Instant.now();
            waitFor(logEventId(), "FAILED");
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(25));
            assertThat(GITHUB.logs.calls).hasValue(3);
            assertThat(count("incidents")).isEqualTo(1);
            assertThat(count("incident_log_snippets")).isZero();
        } finally {
            GITHUB.logs.release.countDown();
        }
    }

    @Test
    void logsWithoutAFailedJobAreExplicitlyUnavailableAndLargeSafeLogsAreBounded() throws Exception {
        GITHUB.workflows.failedJob = false;
        waitFor(requestSync(1), "SUCCEEDED");
        var logs = call("GET", incidentPath(incidentId()) + "/logs", owner.accessToken(), null);
        assertThat(logs.body()).contains("UNAVAILABLE");
        assertThat(count("outbox_events")).isEqualTo(1);
        GITHUB.workflows.failedJob = true;
        GITHUB.workflows.conclusionOverride = "failure";
        GITHUB.logs.content = "x".repeat(40000) + "\npassword=\"" + "secret-value".repeat(1000) + "\"\nUseful failure";
        waitFor(requestSync(2), "SUCCEEDED");
        await().atMost(Duration.ofSeconds(15)).until(() -> count("incident_log_snippets") > 0);
        var all = jdbcTemplate.queryForList("SELECT content FROM incident_log_snippets ORDER BY chunk_index", String.class);
        assertThat(String.join("", all)).hasSizeLessThanOrEqualTo(32768).contains("Useful failure")
            .doesNotContain("secret-value");
    }

    @Test
    void sseDeliversAndReplaysDurableEventsWithAnAuthorizedCursor() throws Exception {
        try (var stream = openStream(owner.accessToken(), null)) {
            waitFor(requestSync(1), "SUCCEEDED");
            var first = readEvent(stream);
            assertThat(first.data()).contains("INCIDENT_CREATED", "incidentId").doesNotContain("private-header");
            waitFor(logEventId(), "SUCCEEDED");
            try (var replay = openStream(owner.accessToken(), first.id())) {
                assertThat(readEvent(replay).data()).contains("INCIDENT_LOGS_READY");
            }
            var foreign = jdbcTemplate.queryForObject(
                "SELECT id FROM notifications WHERE user_id = ? ORDER BY sequence_number LIMIT 1", UUID.class, member.id());
            var response = httpClient.send(streamRequest(owner.accessToken(), foreign.toString()),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void sseClosesAfterMembershipRevocationOrAccessTokenExpiry() throws Exception {
        try (var stream = openStream(member.accessToken(), null)) {
            var closed = stream.lines().readLine();
            assertThat(closed).startsWith(":");
            jdbcTemplate.update("UPDATE workspace_members SET status = 'REMOVED' WHERE workspace_id = ? AND user_id = ?",
                WORKSPACE_ID, member.id());
            assertStreamEnds(stream);
        }
        try (var stream = openStream(shortToken(owner.id()), null)) {
            assertStreamEnds(stream);
        }
    }

    @Test
    void databaseRejectsCrossWorkspaceAndCrossRunIncidentReferences() throws Exception {
        waitFor(requestSync(1), "SUCCEEDED");
        var id = incidentId();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE incidents SET workspace_id = ? WHERE id = ?",
            OTHER_WORKSPACE_ID, id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE notifications SET workspace_id = ? WHERE incident_id = ?",
            OTHER_WORKSPACE_ID, id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void disabledAiRejectsAnalysisWithoutCreatingHistoryOrUsage() throws Exception {
        waitFor(requestSync(1), "SUCCEEDED");
        var path = incidentPath(incidentId()) + "/analyses";
        var response = call("POST", path, owner.accessToken(), Map.of("requestId", UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(count("ai_analyses")).isZero();
        assertThat(count("ai_usage_events")).isZero();
    }

    @Test
    void disabledRagAllowsDraftsButCannotPublishOrSearchVectors() throws Exception {
        var path = basePath() + "/knowledge-articles";
        var created = call("POST", path, owner.accessToken(),
            Map.of("title", "Reviewed fix", "rootCause", "Investigated root cause", "resolution", "Human confirmed fix"));
        assertThat(created.statusCode()).isEqualTo(201);
        var id = body(created).path("id").asString();
        assertThat(call("POST", path + "/" + id + "/approve", owner.accessToken(),
            Map.of("version", 0)).statusCode()).isEqualTo(503);
        assertThat(call("POST", path + "/search", owner.accessToken(),
            Map.of("query", "Investigated root cause", "topK", 3, "mode", "EXACT")).statusCode()).isEqualTo(503);
        assertThat(count("knowledge_chunks")).isZero();
        jdbcTemplate.update("DELETE FROM knowledge_articles WHERE id = CAST(? AS uuid)", id);
    }

    private UUID requestSync(int attempt) throws Exception {
        var path = basePath() + "/repositories/" + REPOSITORY_ID + "/workflow-runs/sync";
        var response = call("POST", path, owner.accessToken(), Map.of("githubRunId", 7000, "runAttempt", attempt));
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(body(response).path("id").asString());
    }

    private void waitFor(UUID id, String status) {
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> assertThat(outboxRepository.find(id).processingStatus()).isEqualTo(status));
    }

    private UUID incidentId() {
        return jdbcTemplate.queryForObject("SELECT id FROM incidents", UUID.class);
    }

    private UUID logEventId() {
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_events WHERE event_type = 'INCIDENT_LOG_CAPTURE'", UUID.class);
    }

    private JsonNode getIncident(UUID id) throws Exception {
        var response = call("GET", incidentPath(id), owner.accessToken(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private HttpResponse<String> changeStatus(Account actor, UUID id, String status, long version) throws Exception {
        return call("PATCH", incidentPath(id) + "/status", actor.accessToken(), Map.of("status", status, "version", version));
    }

    private HttpResponse<String> assign(Account actor, UUID id, UUID assignedTo, long version) throws Exception {
        return call("PATCH", incidentPath(id) + "/assignee", actor.accessToken(), Map.of("assignedTo", assignedTo, "version", version));
    }

    private void publish(String key, UUID id) {
        rabbitTemplate.send(PipelineQueues.EXCHANGE, key,
            new Message(id.toString().getBytes(StandardCharsets.UTF_8), new MessageProperties()));
    }

    private Account createAccount() throws Exception {
        var email = UUID.randomUUID() + "@example.com";
        var password = "Phase04-fixture-password!";
        assertThat(call("POST", "/api/v1/auth/register", null,
            Map.of("email", email, "password", password, "displayName", "Developer")).statusCode()).isEqualTo(201);
        var response = call("POST", "/api/v1/auth/login", null, Map.of("email", email, "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        var result = body(response);
        return new Account(UUID.fromString(result.path("user").path("id").asString()), result.path("accessToken").asString());
    }

    private void insertWorkspace(UUID id, Account creator) {
        jdbcTemplate.update("""
            INSERT INTO workspaces (id, name, slug, created_by, created_at, updated_at)
            VALUES (?, 'Incident fixture', ?, ?, now(), now())
            """, id, id.toString(), creator.id());
    }

    private void insertMember(UUID workspaceId, Account account, String role) {
        jdbcTemplate.update("""
            INSERT INTO workspace_members (id, workspace_id, user_id, role, status, joined_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', now())
            """, UUID.randomUUID(), workspaceId, account.id(), role);
    }

    private String shortToken(UUID userId) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(TokenService.ISSUER).audience(List.of(TokenService.AUDIENCE))
            .subject(userId.toString()).issuedAt(now).expiresAt(now.plusSeconds(2)).claim("token_use", "access").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private StreamResponse openStream(String token, String lastEventId) throws Exception {
        var response = httpClient.send(streamRequest(token, lastEventId), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return new StreamResponse(new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8)));
    }

    private HttpRequest streamRequest(String token, String lastEventId) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + basePath() + "/notifications/stream"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).header("Accept", "text/event-stream");
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        return builder.GET().build();
    }

    private Event readEvent(StreamResponse stream) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var read = executor.submit(() -> {
                String id = null;
                String line;
                while ((line = stream.lines().readLine()) != null) {
                    if (line.startsWith("id:")) {
                        id = line.substring(3).strip();
                    }
                    if (line.startsWith("data:")) {
                        return new Event(id, line.substring(5).strip());
                    }
                }
                throw new IllegalStateException("Stream ended before an event.");
            });
            return read.get(8, TimeUnit.SECONDS);
        }
    }

    private void assertStreamEnds(StreamResponse stream) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var read = executor.submit(() -> {
                while (stream.lines().readLine() != null) {
                    // Drain heartbeats until the server closes the connection.
                }
                return true;
            });
            assertThat(read.get(6, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String basePath() {
        return "/api/v1/workspaces/" + WORKSPACE_ID;
    }

    private String incidentPath(UUID id) {
        return basePath() + "/incidents/" + id;
    }

    private HttpResponse<String> call(String method, String path, String token, Object value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        var publisher = value == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(value));
        return httpClient.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private record Account(UUID id, String accessToken) {
    }

    private record Event(String id, String data) {
    }

    private record StreamResponse(BufferedReader lines) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            lines.close();
        }
    }
}

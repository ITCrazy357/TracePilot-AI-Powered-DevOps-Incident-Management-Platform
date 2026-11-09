package com.tracepilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.tracepilot.audit.AuditAction;
import com.tracepilot.audit.AuditLog;
import com.tracepilot.infrastructure.operations.BacklogMetrics;
import com.tracepilot.infrastructure.security.RateLimitExceeded;
import com.tracepilot.infrastructure.security.RateLimiter;
import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineQueues;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"RATE_LIMIT_ENABLED=true", "RATE_READS_PER_MINUTE=5", "RATE_WRITES_PER_MINUTE=100",
        "RATE_AUTH_PER_MINUTE=1000", "RATE_SESSION_PER_MINUTE=1000", "RATE_LOGIN_PER_ACCOUNT=3",
        "RATE_EXPENSIVE_PER_MINUTE=5", "RAG_ENABLED=false", "AI_ENABLED=false", "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only",
        "AUTH_SIGNING_SECRET=phase08-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase08IntegrationTests {
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID OTHER_WORKSPACE_ID = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();
    private static final UUID REPOSITORY_ID = UUID.randomUUID();
    private static final String SECRET = "phase03-fixture-webhook-secret-not-for-production";
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
    RabbitMQContainer broker;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    AuditLog audit;
    @Autowired
    RateLimiter limiter;
    @Autowired
    BacklogMetrics metrics;
    @Autowired
    MeterRegistry meters;
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
        registry.add("tracepilot.github.webhook-secret", () -> SECRET);
        registry.add("tracepilot.github.api-base-url", GITHUB::baseUrl);
        registry.add("tracepilot.pipeline.enabled", () -> true);
        registry.add("tracepilot.pipeline.retry-delay", () -> "100ms");
        registry.add("tracepilot.pipeline.dispatch-delay-ms", () -> 100);
    }

    @BeforeAll
    void createAccounts() throws Exception {
        owner = createAccount();
        member = createAccount();
        outsider = createAccount();
    }

    @BeforeEach
    void prepareScope() {
        jdbcTemplate.update("DELETE FROM rate_limit_buckets");
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbcTemplate.queryForObject("""
            SELECT count(*) FROM outbox_events WHERE processing_status IN ('PENDING', 'RUNNING', 'RETRY')
                OR status = 'PUBLISHING' OR (processing_status = 'FAILED' AND status <> 'PUBLISHED')
            """, Integer.class) == 0);
        rabbitAdmin.purgeQueue(PipelineQueues.SYNC_QUEUE, false);
        rabbitAdmin.purgeQueue(PipelineQueues.DEAD_QUEUE, false);
        GITHUB.reset();
        for (var table : List.of("knowledge_chunks", "knowledge_articles", "outbox_events", "notifications",
            "incident_log_snippets", "incidents",
            "workflow_jobs", "workflow_runs", "webhook_deliveries",
            "repositories", "github_installations")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id IN (?, ?)", WORKSPACE_ID, OTHER_WORKSPACE_ID);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE workspace_id IN (?, ?)", WORKSPACE_ID, OTHER_WORKSPACE_ID);
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
        GITHUB.workflows.releaseSnapshot.countDown();
        httpClient.close();
        GITHUB.close();
    }

    @Test
    void auditIsAtomicWithWorkspaceCreationAndDoesNotCopyPrivateNames() throws Exception {
        var slug = "audit-" + UUID.randomUUID();
        var response = call("POST", "/api/v1/workspaces", owner.accessToken(),
            Map.of("name", "private-name-not-for-audit", "slug", slug));
        assertThat(response.statusCode()).isEqualTo(201);
        var workspaceId = UUID.fromString(body(response).path("id").asString());
        var trace = response.headers().firstValue("X-Request-ID").orElseThrow();
        UUID.fromString(trace);
        var entry = jdbcTemplate.queryForMap(
            "SELECT action, actor_user_id, metadata::text, request_id FROM audit_logs WHERE workspace_id = ?", workspaceId);
        assertThat(entry.get("action")).isEqualTo("WORKSPACE_CREATED");
        assertThat(entry.get("actor_user_id")).isEqualTo(owner.id());
        assertThat(entry.get("metadata")).isEqualTo("{}");
        assertThat(entry.get("request_id").toString()).isEqualTo(trace);

        jdbcTemplate.execute("""
            CREATE FUNCTION reject_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS
            $$ BEGIN RAISE EXCEPTION 'fixture audit unavailable'; END; $$
            """);
        jdbcTemplate.execute("""
            CREATE TRIGGER reject_audit_fixture BEFORE INSERT ON audit_logs
            FOR EACH ROW EXECUTE FUNCTION reject_audit_fixture()
            """);
        var failedSlug = "rollback-" + UUID.randomUUID();
        try {
            var rejected = call("POST", "/api/v1/workspaces", owner.accessToken(),
                Map.of("name", "Must roll back", "slug", failedSlug));
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(rejected.body()).doesNotContain("fixture audit", "INSERT INTO", "SQLException");
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workspaces WHERE slug = ?", Integer.class, failedSlug)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_audit_fixture ON audit_logs");
            jdbcTemplate.execute("DROP FUNCTION reject_audit_fixture()");
        }
    }

    @Test
    void auditRejectsFreeTextMetadataAndRollsBackTheSurroundingMutation() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE workspaces SET name = 'must-not-commit' WHERE id = ?", WORKSPACE_ID);
            audit.record(WORKSPACE_ID, owner.id(), AuditAction.WORKSPACE_CREATED, WORKSPACE_ID,
                Map.of("secret", "private-token"));
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM workspaces WHERE id = ?", String.class, WORKSPACE_ID))
            .isEqualTo("Pipeline fixture");
    }

    @Test
    void membershipAuditRecordsOnlyRealChangesAndRespectsManagerPermissions() throws Exception {
        var changed = call("PATCH", base() + "/members/" + member.id(), owner.accessToken(), Map.of("role", "ADMIN"));
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(call("PATCH", base() + "/members/" + member.id(), owner.accessToken(),
            Map.of("role", "ADMIN")).statusCode()).isEqualTo(200);
        assertThat(auditCount("MEMBER_ROLE_CHANGED")).isEqualTo(1);
        var entries = call("GET", base() + "/audit-logs?size=1", member.accessToken(), null);
        assertThat(entries.statusCode()).isEqualTo(200);
        assertThat(body(entries).path("total").asLong()).isEqualTo(1);
        assertThat(body(entries).path("items").get(0).path("metadata").path("toRole").asString()).isEqualTo("ADMIN");
        assertThat(call("GET", base() + "/audit-logs", outsider.accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(call("DELETE", base() + "/members/" + member.id(), owner.accessToken(), null).statusCode()).isEqualTo(204);
        assertThat(call("GET", base() + "/audit-logs", member.accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(auditCount("MEMBER_REMOVED")).isEqualTo(1);
    }

    @Test
    void knowledgeAuditOmitsUserAuthoredContentAndTracksDraftRevisions() throws Exception {
        var response = call("POST", base() + "/knowledge-articles", member.accessToken(),
            Map.of("title", "private-knowledge-title", "rootCause", "private-root-description",
                "resolution", "private-resolution-description"));
        assertThat(response.statusCode()).isEqualTo(201);
        var article = body(response);
        var id = article.path("id").asString();
        assertThat(call("PATCH", base() + "/knowledge-articles/" + id, member.accessToken(),
            Map.of("version", article.path("version").asLong(), "title", "Updated",
                "rootCause", "New cause", "resolution", "New fix")).statusCode()).isEqualTo(200);
        var audit = call("GET", base() + "/audit-logs", owner.accessToken(), null);
        assertThat(audit.statusCode()).isEqualTo(200);
        assertThat(audit.body()).contains("KNOWLEDGE_CREATED", "KNOWLEDGE_EDITED")
            .doesNotContain("private-knowledge-title", "private-root-description", "private-resolution-description");
        assertThat(call("GET", base() + "/audit-logs", member.accessToken(), null).statusCode()).isEqualTo(403);
    }

    @Test
    void concurrentRateRequestsShareAnAtomicLimitWithoutStoringTheirIdentity() throws Exception {
        var accepted = new ArrayList<Future<Boolean>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 20; index++) {
                accepted.add(executor.submit(() -> {
                    try {
                        limiter.require("FIXTURE_CONCURRENT", "private-user@example.test", 3, 3600);
                        return true;
                    } catch (RateLimitExceeded rejected) {
                        return false;
                    }
                }));
            }
            long count = 0;
            for (var future : accepted) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    count++;
                }
            }
            assertThat(count).isEqualTo(3);
        }
        var bucket = jdbcTemplate.queryForMap("SELECT key_hash, request_count FROM rate_limit_buckets");
        assertThat(bucket.get("request_count")).isEqualTo(3);
        assertThat(bucket.get("key_hash").toString()).matches("[a-f0-9]{64}").doesNotContain("private-user");
    }

    @Test
    void loginLimitPersistsAcrossRejectedCredentialsAndNormalizesAccountNames() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        for (int index = 0; index < 3; index++) {
            assertThat(call("POST", "/api/v1/auth/login", null,
                Map.of("email", index % 2 == 0 ? email : email.toUpperCase(java.util.Locale.ROOT),
                    "password", "Wrong-password!")).statusCode()).isEqualTo(401);
        }
        var rejected = call("POST", "/api/v1/auth/login", null, Map.of("email", email, "password", "Wrong-password!"));
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 300L);
        assertThat(rejected.body()).doesNotContain(email);
    }

    @Test
    void authenticatedReadsAreLimitedPerUserWhileHealthRemainsAvailable() throws Exception {
        for (int index = 0; index < 5; index++) {
            assertThat(call("GET", base() + "/operations", owner.accessToken(), null).statusCode()).isEqualTo(200);
        }
        var rejected = call("GET", base() + "/operations", owner.accessToken(), null);
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.headers().firstValue("Retry-After")).isPresent();
        assertThat(call("GET", "/actuator/health/liveness", null, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", base() + "/operations", outsider.accessToken(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void expiredRateBucketsAreCleanedWithoutDeletingCurrentBuckets() {
        limiter.require("FIXTURE_CURRENT", "identity", 2, 60);
        jdbcTemplate.update("""
            INSERT INTO rate_limit_buckets (scope, key_hash, bucket_start, expires_at, request_count)
            VALUES ('FIXTURE_OLD', ?, now() - interval '1 day', now() - interval '1 hour', 1)
            """, "a".repeat(64));
        limiter.cleanup();
        assertThat(jdbcTemplate.queryForList("SELECT scope FROM rate_limit_buckets", String.class))
            .containsExactly("FIXTURE_CURRENT");
    }

    @Test
    void requestIdsAreGeneratedLocallyAndInternalActuatorMetricsAreNotPublic() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
            .header("X-Request-ID", "client-controlled-value").GET().build();
        var first = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        var second = call("GET", "/actuator/health", null, null);
        var id = first.headers().firstValue("X-Request-ID").orElseThrow();
        UUID.fromString(id);
        assertThat(id).isNotEqualTo("client-controlled-value")
            .isNotEqualTo(second.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(call("GET", "/actuator/metrics", owner.accessToken(), null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/actuator/prometheus", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void failedWorkflowCanBeReplayedAfterDlqWithoutDuplicatingTheRun() throws Exception {
        GITHUB.workflows.unavailable = true;
        var id = requestSync(owner, 1);
        assertThat(auditCount("WORKFLOW_SYNC_REQUESTED")).isEqualTo(1);
        waitFor(id, "FAILED");
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(publicationStatus(id)));
        assertThat(call("POST", retryPath(id), member.accessToken(), Map.of("replayCount", 0)).statusCode()).isEqualTo(403);
        assertThat(call("POST", retryPath(id), outsider.accessToken(), Map.of("replayCount", 0)).statusCode()).isEqualTo(404);
        GITHUB.workflows.unavailable = false;
        var retry = call("POST", retryPath(id), owner.accessToken(), Map.of("replayCount", 0));
        assertThat(retry.statusCode()).isEqualTo(200);
        waitFor(id, "SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT total_processing_attempts FROM outbox_events WHERE id = ?", Integer.class, id)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT replay_count FROM outbox_events WHERE id = ?", Integer.class, id)).isEqualTo(1);
        publishDuplicate(id);
        assertThat(call("POST", retryPath(id), owner.accessToken(), Map.of("replayCount", 0)).statusCode()).isEqualTo(409);
        assertThat(count("workflow_runs")).isEqualTo(1);
        assertThat(auditCount("OUTBOX_REPLAYED")).isEqualTo(1);
    }

    @Test
    void adminsCannotReplayOwnerOnlyWorkflowSyncAndAiHistoryIsNeverReset() throws Exception {
        jdbcTemplate.update("UPDATE workspace_members SET role = 'ADMIN' WHERE workspace_id = ? AND user_id = ?",
            WORKSPACE_ID, member.id());
        var workflow = failedEvent("WORKFLOW_SYNC");
        assertThat(call("POST", retryPath(workflow), member.accessToken(), Map.of("replayCount", 0)).statusCode()).isEqualTo(403);
        var analysis = failedEvent("AI_ANALYSIS");
        assertThat(call("POST", retryPath(analysis), owner.accessToken(), Map.of("replayCount", 0)).statusCode()).isEqualTo(409);
        assertThat(outboxRepository.find(analysis).processingStatus()).isEqualTo("FAILED");
        assertThat(auditCount("OUTBOX_REPLAYED")).isZero();
    }

    @Test
    void concurrentReplayRequestsAcceptOnlyOneGenerationAndAuditEntry() throws Exception {
        var id = failedEvent("WORKFLOW_SYNC");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var responses = new ArrayList<Future<Integer>>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Replay requests did not start together.");
                    }
                    return call("POST", retryPath(id), owner.accessToken(), Map.of("replayCount", 0)).statusCode();
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var statuses = new ArrayList<Integer>();
            for (var response : responses) {
                statuses.add(response.get(20, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        waitFor(id, "SUCCEEDED");
        assertThat(count("workflow_runs")).isEqualTo(1);
        assertThat(auditCount("OUTBOX_REPLAYED")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT replay_count FROM outbox_events WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void adminCanRecoverFailedLogCaptureWithoutDuplicatingIncidentOrExposingSecrets() throws Exception {
        GITHUB.workflows.failedJob = true;
        GITHUB.logs.unavailable = true;
        waitFor(requestSync(owner, 1), "SUCCEEDED");
        var id = jdbcTemplate.queryForObject(
            "SELECT id FROM outbox_events WHERE event_type = 'INCIDENT_LOG_CAPTURE'", UUID.class);
        waitFor(id, "FAILED");
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(publicationStatus(id)));
        assertThat(count("incident_log_snippets")).isZero();
        assertThat(call("POST", retryPath(id), member.accessToken(), Map.of("replayCount", 0)).statusCode())
            .isEqualTo(403);
        jdbcTemplate.update("UPDATE workspace_members SET role = 'ADMIN' WHERE workspace_id = ? AND user_id = ?",
            WORKSPACE_ID, member.id());
        GITHUB.logs.unavailable = false;
        assertThat(call("POST", retryPath(id), member.accessToken(), Map.of("replayCount", 0)).statusCode())
            .isEqualTo(200);
        waitFor(id, "SUCCEEDED");
        var content = String.join("\n", jdbcTemplate.queryForList(
            "SELECT content FROM incident_log_snippets ORDER BY chunk_index", String.class));
        assertThat(content).contains("Error: expected HTTP 200 but got 500")
            .doesNotContain("private-header-value", "database-private-value", "private-url-value",
                GitHubApiFixture.INSTALLATION_TOKEN);
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("ai_analyses")).isZero();
        assertThat(auditCount("OUTBOX_REPLAYED")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT total_processing_attempts FROM outbox_events WHERE id = ?", Integer.class, id)).isEqualTo(4);
    }

    @Test
    void replayRejectsInactiveSourcesAndForeignWorkspaceEventsWithoutChangingHistory() throws Exception {
        var id = failedEvent("WORKFLOW_SYNC");
        var foreignPath = "/api/v1/workspaces/" + OTHER_WORKSPACE_ID + "/operations/outbox/" + id + "/retry";
        assertThat(call("POST", foreignPath, outsider.accessToken(), Map.of("replayCount", 0)).statusCode())
            .isEqualTo(404);
        jdbcTemplate.update("UPDATE repositories SET is_active = false WHERE id = ?", REPOSITORY_ID);
        assertThat(call("POST", retryPath(id), owner.accessToken(), Map.of("replayCount", 0)).statusCode())
            .isEqualTo(409);
        jdbcTemplate.update("UPDATE repositories SET is_active = true WHERE id = ?", REPOSITORY_ID);
        jdbcTemplate.update("UPDATE github_installations SET status = 'SUSPENDED' WHERE id = ?", INSTALLATION_ID);
        assertThat(call("POST", retryPath(id), owner.accessToken(), Map.of("replayCount", 0)).statusCode())
            .isEqualTo(409);
        assertThat(outboxRepository.find(id).processingStatus()).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT replay_count FROM outbox_events WHERE id = ?", Integer.class, id)).isZero();
        assertThat(auditCount("OUTBOX_REPLAYED")).isZero();
    }

    @Test
    void aStaleWorkerCannotOverwriteTheResultOfAReplayedGeneration() throws Exception {
        var id = failedEvent("WORKFLOW_SYNC");
        var transaction = new TransactionTemplate(transactionManager);
        var claim = transaction.execute(status -> {
            jdbcTemplate.update(
                """
                UPDATE outbox_events SET status = 'PENDING', processing_status = 'PENDING',
                    processing_attempts = 0 WHERE id = ?
                """, id);
            return outboxRepository.claimProcessing(id).orElseThrow();
        });
        jdbcTemplate.update("""
            UPDATE outbox_events SET processing_lease_until = now() - interval '1 minute',
                processing_attempts = 3 WHERE id = ?
            """, id);
        outboxRepository.recoverExpired();
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(publicationStatus(id)));
        assertThat(call("POST", retryPath(id), owner.accessToken(), Map.of("replayCount", 0)).statusCode()).isEqualTo(200);
        waitFor(id, "SUCCEEDED");
        outboxRepository.processingFailed(claim, "STALE_FIXTURE");
        assertThat(outboxRepository.find(id).processingStatus()).isEqualTo("SUCCEEDED");
        assertThat(outboxRepository.find(id).lastErrorCode()).isNull();
    }

    @Test
    void operationsAndMetricsCountOnlyTheCorrectWorkspaceWithoutExposingPayload() throws Exception {
        var id = failedEvent("AI_ANALYSIS");
        var response = call("GET", base() + "/operations/outbox?size=1", owner.accessToken(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("items").get(0).path("id").asString()).isEqualTo(id.toString());
        assertThat(response.body()).doesNotContain("payload", "raw-payload-must-not-be-stored");
        assertThat(call("GET", base() + "/operations", member.accessToken(), null).statusCode()).isEqualTo(403);
        var other = call("GET", "/api/v1/workspaces/" + OTHER_WORKSPACE_ID + "/operations", outsider.accessToken(), null);
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(body(other).isEmpty()).isTrue();
        metrics.sample();
        assertThat(meters.find("tracepilot.outbox.backlog").tag("event", "AI_ANALYSIS").tag("state", "FAILED")
            .gauge().value()).isEqualTo(1);
    }

    @Test
    void readinessTracksBrokerOutagesWithoutMarkingTheProcessDead() throws Exception {
        assertThat(broker.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            assertThat(call("GET", "/actuator/health/readiness", null, null).statusCode()).isEqualTo(503);
            assertThat(call("GET", "/actuator/health/liveness", null, null).statusCode()).isEqualTo(200);
        } finally {
            assertThat(broker.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(call("GET", "/actuator/health/readiness", null, null).statusCode()).isEqualTo(200));
    }

    private UUID failedEvent(String eventType) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status,
                processing_status, processing_attempts, total_processing_attempts, created_at, available_at, processed_at)
            VALUES (?, 'REPOSITORY', ?, ?, '{"runId":7000,"runAttempt":1}', 'PUBLISHED', 'FAILED', 3, 3, now(), now(), now())
            """, id, REPOSITORY_ID, eventType);
        return id;
    }

    @Test
    void forwardedClientAddressesCannotBypassThePeerIpLimit() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        assertThat(call("POST", "/api/v1/auth/login", null,
            Map.of("email", email, "password", "Wrong-password!")).statusCode()).isEqualTo(401);
        jdbcTemplate.update("UPDATE rate_limit_buckets SET request_count = 1000 WHERE scope = 'AUTH_ENTRY'");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json").header("X-Forwarded-For", "203.0.113.10")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json(Map.of("email", email, "password", "Wrong-password!"))))
            .build();
        assertThat(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
    }

    @Test
    void expensiveRequestsHaveTheirOwnBudgetBeforeBusinessWorkStarts() throws Exception {
        var path = "/api/v1/workspaces/" + UUID.randomUUID() + "/repositories/"
            + UUID.randomUUID() + "/workflow-runs/sync";
        for (int index = 0; index < 5; index++) {
            assertThat(call("POST", path, owner.accessToken(),
                Map.of("githubRunId", 7000, "runAttempt", 1)).statusCode()).isEqualTo(404);
        }
        assertThat(call("POST", path, owner.accessToken(),
            Map.of("githubRunId", 7000, "runAttempt", 1)).statusCode()).isEqualTo(429);
        assertThat(count("outbox_events")).isZero();
    }

    private long auditCount(String action) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE workspace_id = ? AND action = ?",
            Long.class, WORKSPACE_ID, action);
    }

    private String base() {
        return "/api/v1/workspaces/" + WORKSPACE_ID;
    }

    private String retryPath(UUID id) {
        return base() + "/operations/outbox/" + id + "/retry";
    }

    private UUID requestSync(Account account, int attempt) throws Exception {
        var response = call("POST", path("/sync"), account.accessToken(),
            Map.of("githubRunId", 7000, "runAttempt", attempt));
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(body(response).path("id").asString());
    }

    private void waitFor(UUID id, String state) {
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> assertThat(outboxRepository.find(id).processingStatus()).isEqualTo(state));
    }

    private String publicationStatus(UUID id) {
        return outboxRepository.find(id).publicationStatus();
    }

    private void publishDuplicate(UUID id) {
        rabbitTemplate.send(PipelineQueues.EXCHANGE, PipelineQueues.SYNC_KEY,
            new Message(id.toString().getBytes(StandardCharsets.UTF_8), new MessageProperties()));
    }

    private Account createAccount() throws Exception {
        var email = UUID.randomUUID() + "@example.com";
        var password = "Phase08-fixture-password!";
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
            VALUES (?, 'Pipeline fixture', ?, ?, now(), now())
            """, id, id.toString(), creator.id());
    }

    private void insertMember(UUID workspaceId, Account account, String role) {
        jdbcTemplate.update("""
            INSERT INTO workspace_members (id, workspace_id, user_id, role, status, joined_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', now())
            """, UUID.randomUUID(), workspaceId, account.id(), role);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String path(String suffix) {
        return "/api/v1/workspaces/" + WORKSPACE_ID + "/repositories/" + REPOSITORY_ID + "/workflow-runs" + suffix;
    }

    private HttpResponse<String> call(String method, String path, String token, Object value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        var publisher = value == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json(value));
        return httpClient.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private byte[] json(Object value) {
        return objectMapper.writeValueAsBytes(value);
    }

    private JsonNode body(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private record Account(UUID id, String accessToken) {
    }
}

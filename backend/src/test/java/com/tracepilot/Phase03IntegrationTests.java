package com.tracepilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineDtos;
import com.tracepilot.pipeline.PipelineQueues;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
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
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only",
        "AUTH_SIGNING_SECRET=phase03-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase03IntegrationTests {
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
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbcTemplate.queryForObject("""
            SELECT count(*) FROM outbox_events WHERE processing_status IN ('PENDING', 'RUNNING', 'RETRY')
                OR status = 'PUBLISHING' OR (processing_status = 'FAILED' AND status <> 'PUBLISHED')
            """, Integer.class) == 0);
        rabbitAdmin.purgeQueue(PipelineQueues.SYNC_QUEUE, false);
        rabbitAdmin.purgeQueue(PipelineQueues.DEAD_QUEUE, false);
        GITHUB.reset();
        for (var table : List.of("outbox_events", "workflow_jobs", "workflow_runs", "webhook_deliveries",
            "repositories", "github_installations")) {
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
        GITHUB.workflows.releaseSnapshot.countDown();
        httpClient.close();
        GITHUB.close();
    }

    @Test
    void workflowWebhookFetchesAllJobPagesAndServesWorkspaceScopedApis() throws Exception {
        GITHUB.workflows.jobCount = 101;
        var delivery = UUID.randomUUID().toString();
        var response = webhook("workflow_run", delivery, workflowPayload(1));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("status").asString()).isEqualTo("PROCESSED");
        var id = eventForDelivery(delivery);
        waitFor(id, "SUCCEEDED");
        assertThat(count("workflow_runs")).isEqualTo(1);
        assertThat(count("workflow_jobs")).isEqualTo(101);
        assertThat(GITHUB.workflows.jobCalls).hasValue(2);
        var runs = call("GET", path(""), member.accessToken(), null);
        assertThat(runs.statusCode()).isEqualTo(200);
        var run = body(runs).path("items").get(0);
        assertThat(run.path("runAttempt").asInt()).isEqualTo(1);
        assertThat(run.path("conclusion").asString()).isEqualTo("failure");
        assertThat(run.path("completedAt").isNull()).isTrue();
        var jobs = call("GET", path("/" + run.path("id").asString() + "/jobs"), member.accessToken(), null);
        assertThat(jobs.statusCode()).isEqualTo(200);
        assertThat(body(jobs).size()).isEqualTo(101);
        assertThat(call("GET", path("/sync-requests"), member.accessToken(), null).body()).contains("SUCCEEDED");
    }

    @Test
    void duplicateWebhooksAndRabbitMessagesDoNotCreateDuplicateRunsOrJobs() throws Exception {
        var delivery = UUID.randomUUID().toString();
        var payload = workflowPayload(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return webhook("workflow_run", delivery, payload);
            });
            var second = executor.submit(() -> {
                start.await();
                return webhook("workflow_run", delivery, payload);
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        var id = eventForDelivery(delivery);
        waitFor(id, "SUCCEEDED");
        assertThat(count("outbox_events")).isEqualTo(1);
        int calls = GITHUB.workflows.runCalls.get();
        publishDuplicate(id);
        var next = requestSync(owner, 1);
        waitFor(next, "SUCCEEDED");
        assertThat(count("workflow_runs")).isEqualTo(1);
        assertThat(count("workflow_jobs")).isEqualTo(2);
        assertThat(GITHUB.workflows.runCalls.get()).isEqualTo(calls + 1);
    }

    @Test
    void rerunUsesASeparateAttemptAndKeepsTheOriginalResult() throws Exception {
        waitFor(requestSync(owner, 1), "SUCCEEDED");
        waitFor(requestSync(owner, 2), "SUCCEEDED");
        assertThat(count("workflow_runs")).isEqualTo(2);
        assertThat(count("workflow_jobs")).isEqualTo(4);
        assertThat(jdbcTemplate.queryForList("SELECT conclusion FROM workflow_runs ORDER BY run_attempt", String.class))
            .containsExactly("failure", "success");
    }

    @Test
    void olderSnapshotsCannotRegressACompletedAttempt() throws Exception {
        waitFor(requestSync(owner, 1), "SUCCEEDED");
        GITHUB.workflows.status = "in_progress";
        GITHUB.workflows.updatedAt = "2026-11-07T00:00:00Z";
        waitFor(requestSync(owner, 1), "SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM workflow_runs", String.class)).isEqualTo("completed");
        assertThat(jdbcTemplate.queryForList("SELECT status FROM workflow_jobs", String.class))
            .containsOnly("completed");
    }

    @Test
    void failuresRetryWithBackoffThenReachTheRealDeadLetterQueue() throws Exception {
        GITHUB.workflows.unavailable = true;
        var id = requestSync(owner, 1);
        waitFor(id, "FAILED");
        await().atMost(Duration.ofSeconds(20)).until(() -> "PUBLISHED".equals(publicationStatus(id)));
        var dead = rabbitTemplate.receive(PipelineQueues.DEAD_QUEUE, 3000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(id.toString());
        assertThat(outboxRepository.find(id).attempts()).isEqualTo(3);
        assertThat(GITHUB.workflows.runCalls).hasValue(9);
        assertThat(count("workflow_runs")).isZero();
        GITHUB.workflows.unavailable = false;
        var retry = requestSync(owner, 1);
        assertThat(retry).isNotEqualTo(id);
        waitFor(retry, "SUCCEEDED");
        assertThat(outboxRepository.find(id).processingStatus()).isEqualTo("FAILED");
        assertThat(count("workflow_runs")).isEqualTo(1);
    }

    @Test
    void partialOrWrongAttemptSnapshotsNeverReplaceAValidRun() throws Exception {
        waitFor(requestSync(owner, 1), "SUCCEEDED");
        GITHUB.workflows.partialJobs = true;
        waitFor(requestSync(owner, 1), "FAILED");
        assertThat(count("workflow_jobs")).isEqualTo(2);
        GITHUB.workflows.partialJobs = false;
        GITHUB.workflows.wrongAttempt = true;
        waitFor(requestSync(owner, 2), "FAILED");
        assertThat(count("workflow_runs")).isEqualTo(1);
    }

    @Test
    void webhookRespondsWithoutWaitingForGitHubAndRevocationPreventsCommit() throws Exception {
        GITHUB.workflows.pauseJobs = true;
        var delivery = UUID.randomUUID().toString();
        var started = Instant.now();
        try {
            assertThat(webhook("workflow_run", delivery, workflowPayload(1)).statusCode()).isEqualTo(200);
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(4));
            assertThat(GITHUB.workflows.snapshotRequested.await(5, TimeUnit.SECONDS)).isTrue();
            var suspend = Map.of("action", "suspend", "installation", Map.of("id", 501));
            assertThat(webhook("installation", UUID.randomUUID().toString(), json(suspend)).statusCode()).isEqualTo(200);
        } finally {
            GITHUB.workflows.releaseSnapshot.countDown();
        }
        waitFor(eventForDelivery(delivery), "IGNORED");
        assertThat(count("workflow_runs")).isZero();
        assertThat(count("workflow_jobs")).isZero();
    }

    @Test
    void authorizationAndRepositoryScopeAreCheckedForReadsAndManualSync() throws Exception {
        var denied = call("POST", path("/sync"), member.accessToken(), Map.of("githubRunId", 7000, "runAttempt", 1));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(call("GET", path(""), outsider.accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(call("GET", path(""), null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", path("?size=101"), owner.accessToken(), null).statusCode()).isEqualTo(400);
        assertThat(call("POST", path("/sync"), owner.accessToken(), Map.of("githubRunId", 7000, "runAttempt", 0))
            .statusCode()).isEqualTo(400);
        waitFor(requestSync(owner, 1), "SUCCEEDED");
        var runId = jdbcTemplate.queryForObject("SELECT id FROM workflow_runs", UUID.class);
        var otherRepo = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO repositories (id, workspace_id, installation_id, github_repository_id,
                full_name, is_active, created_at, updated_at) VALUES (?, ?, ?, 10001, 'acme/other', true, now(), now())
            """, otherRepo, WORKSPACE_ID, INSTALLATION_ID);
        var otherPath = "/api/v1/workspaces/" + WORKSPACE_ID + "/repositories/" + otherRepo + "/workflow-runs/" + runId;
        assertThat(call("GET", otherPath, owner.accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(call("GET", path("/" + runId + "/jobs"), outsider.accessToken(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void invalidWebhookRollsBackDeliveryAndInactiveRepositoryIsIgnored() throws Exception {
        var invalid = json(Map.of("action", "completed", "installation", Map.of("id", 501),
            "repository", Map.of("id", 10000), "workflow_run", Map.of("id", 7000, "run_attempt", 0)));
        assertThat(webhook("workflow_run", UUID.randomUUID().toString(), invalid).statusCode()).isEqualTo(400);
        assertThat(count("webhook_deliveries")).isZero();
        assertThat(count("outbox_events")).isZero();
        jdbcTemplate.update("UPDATE repositories SET is_active = false WHERE id = ?", REPOSITORY_ID);
        var ignored = webhook("workflow_run", UUID.randomUUID().toString(), workflowPayload(1));
        assertThat(body(ignored).path("status").asString()).isEqualTo("IGNORED");
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void anExpiredWorkerLeaseIsRecoveredWithoutLosingTheRequest() {
        var transaction = new TransactionTemplate(transactionManager);
        var id = transaction.execute(status -> {
            var eventId = outboxRepository.enqueue(REPOSITORY_ID, new PipelineDtos.Work(7000, 1), Optional.empty());
            jdbcTemplate.update("""
                UPDATE outbox_events SET processing_status = 'RUNNING', processing_attempts = 1,
                    processing_token = ?, processing_lease_until = now() - interval '1 minute', status = 'PUBLISHED'
                WHERE id = ?
                """, UUID.randomUUID(), eventId);
            return eventId;
        });
        waitFor(id, "SUCCEEDED");
        assertThat(outboxRepository.find(id).attempts()).isEqualTo(2);
        assertThat(count("workflow_runs")).isEqualTo(1);
    }

    @Test
    void unroutableMessagesStayInTheOutboxUntilTheBindingRecovers() throws Exception {
        var binding = new Binding(PipelineQueues.SYNC_QUEUE, Binding.DestinationType.QUEUE,
            PipelineQueues.EXCHANGE, PipelineQueues.SYNC_KEY, null);
        UUID id;
        rabbitAdmin.removeBinding(binding);
        try {
            id = requestSync(owner, 1);
            await().atMost(Duration.ofSeconds(15)).until(() ->
                "BROKER_UNAVAILABLE".equals(outboxRepository.find(id).lastErrorCode()));
            assertThat(outboxRepository.find(id).processingStatus()).isEqualTo("PENDING");
            assertThat(count("workflow_runs")).isZero();
        } finally {
            rabbitAdmin.declareBinding(binding);
        }
        jdbcTemplate.update("UPDATE outbox_events SET available_at = now() WHERE id = ?", id);
        waitFor(id, "SUCCEEDED");
    }

    @Test
    void brokerOutageDoesNotLoseAnAcceptedWebhook() throws Exception {
        var delivery = UUID.randomUUID().toString();
        assertThat(broker.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            assertThat(webhook("workflow_run", delivery, workflowPayload(1)).statusCode()).isEqualTo(200);
            var id = eventForDelivery(delivery);
            await().atMost(Duration.ofSeconds(20)).until(() ->
                "BROKER_UNAVAILABLE".equals(outboxRepository.find(id).lastErrorCode()));
            assertThat(count("workflow_runs")).isZero();
        } finally {
            assertThat(broker.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        waitFor(eventForDelivery(delivery), "SUCCEEDED");
        assertThat(count("workflow_runs")).isEqualTo(1);
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

    private UUID eventForDelivery(String delivery) {
        return jdbcTemplate.queryForObject("""
            SELECT o.id FROM outbox_events o JOIN webhook_deliveries d ON d.id = o.source_delivery_id
            WHERE d.github_delivery_id = ?
            """, UUID.class, delivery);
    }

    private String publicationStatus(UUID id) {
        return outboxRepository.find(id).publicationStatus();
    }

    private byte[] workflowPayload(int attempt) {
        return json(Map.of("action", "completed", "installation", Map.of("id", 501),
            "repository", Map.of("id", 10000), "workflow_run", Map.of("id", 7000, "run_attempt", attempt),
            "sender", Map.of("private_marker", "raw-payload-must-not-be-stored")));
    }

    private void publishDuplicate(UUID id) {
        rabbitTemplate.send(PipelineQueues.EXCHANGE, PipelineQueues.SYNC_KEY,
            new Message(id.toString().getBytes(StandardCharsets.UTF_8), new MessageProperties()));
    }

    private Account createAccount() throws Exception {
        var email = UUID.randomUUID() + "@example.com";
        var password = "Phase03-fixture-password!";
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

    private HttpResponse<String> webhook(String event, String delivery, byte[] bytes) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/webhooks/github"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .header("X-GitHub-Event", event).header("X-GitHub-Delivery", delivery)
            .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(bytes)))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

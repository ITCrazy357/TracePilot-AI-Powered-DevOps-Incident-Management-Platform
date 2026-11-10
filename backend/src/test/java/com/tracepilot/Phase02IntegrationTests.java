package com.tracepilot;

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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"RATE_LIMIT_ENABLED=false", "RAG_ENABLED=false", "AI_ENABLED=false", "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only", "PIPELINE_ENABLED=false",
        "AUTH_SIGNING_SECRET=phase02-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase02IntegrationTests {
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID OTHER_WORKSPACE_ID = UUID.randomUUID();
    private static final String WEBHOOK_SECRET = "phase02-fixture-webhook-secret-not-for-production";
    private static final GitHubApiFixture GITHUB = new GitHubApiFixture();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Value("${local.server.port}")
    int port;
    private Account owner;
    private Account member;
    private Account outsider;

    @DynamicPropertySource
    static void gitHubProperties(DynamicPropertyRegistry registry) {
        registry.add("tracepilot.github.enabled", () -> true);
        registry.add("tracepilot.github.app-id", () -> GitHubApiFixture.APP_ID);
        registry.add("tracepilot.github.installation-id", () -> GitHubApiFixture.INSTALLATION_ID);
        registry.add("tracepilot.github.workspace-id", WORKSPACE_ID::toString);
        registry.add("tracepilot.github.private-key-path", () -> GITHUB.keyPath.toString());
        registry.add("tracepilot.github.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("tracepilot.github.api-base-url", GITHUB::baseUrl);
    }

    @BeforeAll
    void createAccounts() throws Exception {
        owner = createAccount();
        member = createAccount();
        outsider = createAccount();
    }

    @BeforeEach
    void prepareWorkspaces() {
        GITHUB.reset();
        jdbcTemplate.update("DELETE FROM webhook_deliveries");
        jdbcTemplate.update("DELETE FROM repositories");
        jdbcTemplate.update("DELETE FROM github_installations");
        jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id IN (?, ?)", WORKSPACE_ID, OTHER_WORKSPACE_ID);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE workspace_id IN (?, ?)", WORKSPACE_ID, OTHER_WORKSPACE_ID);
        jdbcTemplate.update("DELETE FROM workspaces WHERE id IN (?, ?)", WORKSPACE_ID, OTHER_WORKSPACE_ID);
        insertWorkspace(WORKSPACE_ID, owner);
        insertWorkspace(OTHER_WORKSPACE_ID, outsider);
        insertMember(WORKSPACE_ID, owner, "OWNER");
        insertMember(WORKSPACE_ID, member, "MEMBER");
        insertMember(OTHER_WORKSPACE_ID, outsider, "OWNER");
    }

    @AfterAll
    void closeClients() throws Exception {
        httpClient.close();
        GITHUB.close();
    }

    @Test
    void ownerSelectsRepositoriesIndependentlyInTwoWorkspacesAndPreservesHistory() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        jdbcTemplate.update("UPDATE workspace_members SET role = 'MEMBER' WHERE workspace_id = ? AND role = 'OWNER'", OTHER_WORKSPACE_ID);
        insertMember(OTHER_WORKSPACE_ID, owner, "OWNER");
        var target = "/api/v1/workspaces/" + OTHER_WORKSPACE_ID + "/github";
        var originalId = jdbcTemplate.queryForObject("SELECT id FROM repositories WHERE workspace_id = ? AND github_repository_id = 10000",
            UUID.class, WORKSPACE_ID);
        assertThat(body(call("GET", target + "/connection", owner.accessToken(), null)).path("installations").size()).isEqualTo(1);
        assertThat(call("GET", target + "/installations/501/repositories", owner.accessToken(), null).statusCode()).isEqualTo(200);
        assertThat(count("repositories")).isEqualTo(2);
        var created = call("POST", target + "/connection", owner.accessToken(),
            Map.of("installationId", 501, "repositoryIds", List.of(10001)));
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(count("github_installations")).isEqualTo(2);
        assertThat(synchronize(owner, OTHER_WORKSPACE_ID).statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForList("SELECT github_repository_id FROM repositories WHERE workspace_id = ?", Long.class,
            OTHER_WORKSPACE_ID)).containsExactly(10001L);
        var source = "/api/v1/workspaces/" + WORKSPACE_ID + "/github/connection";
        long version = body(call("GET", source, owner.accessToken(), null)).path("connection").path("version").asLong();
        var selected = Map.of("installationId", 501, "version", version, "repositoryIds", List.of(10000));
        assertThat(call("POST", source, owner.accessToken(), selected).statusCode()).isEqualTo(200);
        assertThat(call("POST", source, owner.accessToken(), selected).statusCode()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM repositories WHERE workspace_id = ? AND github_repository_id = 10000",
            UUID.class, WORKSPACE_ID)).isEqualTo(originalId);
        var added = selectionPayload(List.of(Map.of("id", 10001, "full_name", "acme/repo-1")), List.of());
        assertThat(deliver("installation_repositories", added).statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM repositories WHERE workspace_id = ? AND github_repository_id = 10001",
            Boolean.class, WORKSPACE_ID)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM repositories WHERE workspace_id = ? AND github_repository_id = 10001",
            Boolean.class, OTHER_WORKSPACE_ID)).isTrue();
        assertThat(call("POST", target + "/connection", owner.accessToken(),
            Map.of("installationId", 501, "version", 2, "repositoryIds", List.of(999999))).statusCode()).isEqualTo(409);
        assertThat(deliver("installation", installationPayload("suspend")).statusCode()).isEqualTo(200);
        assertThat(activeRepositoryCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM github_installations WHERE status = 'SUSPENDED'", Integer.class)).isEqualTo(2);
    }

    @Test
    void knowingAnInstallationIdDoesNotGrantCatalogOrConnectionAccess() throws Exception {
        var target = "/api/v1/workspaces/" + OTHER_WORKSPACE_ID + "/github";
        assertThat(body(call("GET", target + "/connection", outsider.accessToken(), null)).path("installations")).isEmpty();
        assertThat(call("GET", target + "/installations/501/repositories", outsider.accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(call("POST", target + "/connection", outsider.accessToken(),
            Map.of("installationId", 501, "repositoryIds", List.of(10000))).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/workspaces/" + WORKSPACE_ID + "/github/connection", member.accessToken(), null).statusCode()).isEqualTo(403);
        assertThat(GITHUB.metadataRequests).hasValue(0);
    }

    @Test
    void sourcePermissionWebhookDuringFetchRejectsAStaleNewConnection() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        jdbcTemplate.update("UPDATE workspace_members SET role = 'MEMBER' WHERE workspace_id = ? AND role = 'OWNER'", OTHER_WORKSPACE_ID);
        insertMember(OTHER_WORKSPACE_ID, owner, "OWNER");
        GITHUB.pauseRepositories = true;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> call("POST", "/api/v1/workspaces/" + OTHER_WORKSPACE_ID + "/github/connection",
                owner.accessToken(), Map.of("installationId", 501, "repositoryIds", List.of(10001))));
            try {
                assertThat(GITHUB.snapshotRequested.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(deliver("installation_repositories", selectionPayload(List.of(), List.of(Map.of("id", 10001))))
                    .statusCode()).isEqualTo(200);
            } finally { GITHUB.releaseSnapshot.countDown(); }
            assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }
        assertThat(count("github_installations")).isEqualTo(1);
    }

    @Test
    void revokingSourceOwnershipDuringTheRemoteFetchPreventsCreatingAConnection() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        jdbcTemplate.update("UPDATE workspace_members SET role = 'MEMBER' WHERE workspace_id = ? AND role = 'OWNER'", OTHER_WORKSPACE_ID);
        insertMember(OTHER_WORKSPACE_ID, owner, "OWNER");
        GITHUB.pauseRepositories = true;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> call("POST", "/api/v1/workspaces/" + OTHER_WORKSPACE_ID + "/github/connection",
                owner.accessToken(), Map.of("installationId", 501, "repositoryIds", List.of(10001))));
            try {
                assertThat(GITHUB.snapshotRequested.await(5, TimeUnit.SECONDS)).isTrue();
                jdbcTemplate.update("UPDATE workspace_members SET status = 'REMOVED' WHERE workspace_id = ? AND user_id = ?", WORKSPACE_ID, owner.id());
            } finally { GITHUB.releaseSnapshot.countDown(); }
            assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(404);
        }
        assertThat(count("github_installations")).isEqualTo(1);
    }

    @Test
    void ownerSynchronizesPaginatedRepositoriesWithoutPersistingCredentials() throws Exception {
        GITHUB.repositoryCount = 101;
        var response = synchronize(owner, WORKSPACE_ID);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("repositoryCount").asInt()).isEqualTo(101);
        assertThat(response.body()).doesNotContain(GitHubApiFixture.INSTALLATION_TOKEN, "PRIVATE KEY");
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        assertThat(count("github_installations")).isEqualTo(1);
        assertThat(count("repositories")).isEqualTo(101);

        var page = call("GET", "/api/v1/workspaces/" + WORKSPACE_ID + "/repositories?page=1&size=20",
            member.accessToken(), null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(body(page).path("items").size()).isEqualTo(20);
        assertThat(body(page).path("totalElements").asLong()).isEqualTo(101);
        var columns = jdbcTemplate.queryForList("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name IN ('github_installations', 'repositories', 'webhook_deliveries')
            """, String.class);
        assertThat(columns).doesNotContain("private_key", "access_token", "token", "payload", "raw_body");
    }

    @Test
    void workspacePermissionsAndOperatorBindingAreEnforcedBeforeCallingGitHub() throws Exception {
        assertThat(synchronize(member, WORKSPACE_ID).statusCode()).isEqualTo(403);
        assertThat(synchronize(outsider, WORKSPACE_ID).statusCode()).isEqualTo(404);
        assertThat(synchronize(outsider, OTHER_WORKSPACE_ID).statusCode()).isEqualTo(409);
        assertThat(call("POST", "/api/v1/workspaces/" + WORKSPACE_ID + "/github/sync", null, null)
            .statusCode()).isEqualTo(401);
        assertThat(GITHUB.metadataRequests).hasValue(0);
        assertThat(call("GET", "/api/v1/workspaces/" + WORKSPACE_ID + "/repositories",
            outsider.accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/workspaces/" + WORKSPACE_ID + "/repositories?size=101",
            owner.accessToken(), null).statusCode()).isEqualTo(400);
    }

    @Test
    void webhookAuthenticatesExactRawBytesBeforeParsingOrWriting() throws Exception {
        byte[] raw = "{ \"zen\": \"Xin chào\", \"sender\":{\"token\":\"private-payload-marker\"} }"
            .getBytes(StandardCharsets.UTF_8);
        var delivery = UUID.randomUUID().toString();
        var valid = webhook("ping", delivery, raw, signature(raw));
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(body(valid).path("status").asString()).isEqualTo("PROCESSED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT payload_sha256 FROM webhook_deliveries WHERE github_delivery_id = ?", String.class, delivery))
            .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        byte[] altered = new String(raw, StandardCharsets.UTF_8).replace("Xin chào", "Modified")
            .getBytes(StandardCharsets.UTF_8);
        assertThat(webhook("ping", UUID.randomUUID().toString(), altered, signature(raw)).statusCode()).isEqualTo(401);
        assertThat(webhook("ping", UUID.randomUUID().toString(), raw, "sha256=" + "0".repeat(64))
            .statusCode()).isEqualTo(401);
        assertThat(webhook("ping", UUID.randomUUID().toString(), raw, "sha256=invalid").statusCode()).isEqualTo(401);
        assertThat(webhook("ping", UUID.randomUUID().toString(), raw, null).statusCode()).isEqualTo(401);
        byte[] invalidJson = "not-json".getBytes(StandardCharsets.UTF_8);
        assertThat(webhook("ping", UUID.randomUUID().toString(), invalidJson, null).statusCode()).isEqualTo(401);
        assertThat(webhook("ping", UUID.randomUUID().toString(), invalidJson, signature(invalidJson))
            .statusCode()).isEqualTo(400);
        assertThat(count("webhook_deliveries")).isEqualTo(1);
    }

    @Test
    void duplicateDeliveryIsIdempotentAndConflictingReuseIsRejected() throws Exception {
        var raw = json(Map.of("zen", "delivery"));
        var id = UUID.randomUUID().toString();
        assertThat(webhook("ping", id, raw, signature(raw)).statusCode()).isEqualTo(200);
        var duplicate = webhook("ping", id, raw, signature(raw));
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(body(duplicate).path("duplicate").asBoolean()).isTrue();
        var changed = json(Map.of("zen", "other delivery"));
        assertThat(webhook("ping", id, changed, signature(changed)).statusCode()).isEqualTo(409);
        assertThat(webhook("workflow_run", id, raw, signature(raw)).statusCode()).isEqualTo(409);
        assertThat(count("webhook_deliveries")).isEqualTo(1);
    }

    @Test
    void concurrentDeliveryAppliesTheRepositoryChangeOnce() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        var raw = selectionPayload(List.of(Map.of("id", 20000, "full_name", "acme/added")), List.of());
        var signature = signature(raw);
        var id = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return webhook("installation_repositories", id, raw, signature);
            });
            var second = executor.submit(() -> {
                start.await();
                return webhook("installation_repositories", id, raw, signature);
            });
            start.countDown();
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
            assertThat(responses.stream().map(response -> body(response).path("duplicate").asBoolean()).toList())
                .containsExactlyInAnyOrder(false, true);
        }
        assertThat(count("webhook_deliveries")).isEqualTo(1);
        assertThat(count("repositories")).isEqualTo(2);
    }

    @Test
    void selectionAndInstallationLifecycleUpdateOnlyTheBoundInstallation() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        var selection = selectionPayload(List.of(Map.of("id", 20000, "full_name", "acme/added")),
            List.of(Map.of("id", 10000)));
        assertThat(deliver("installation_repositories", selection).statusCode()).isEqualTo(200);
        assertThat(activeRepositoryCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM repositories WHERE github_repository_id = 20000",
            Integer.class)).isZero();
        var unknown = json(Map.of("action", "deleted", "installation", Map.of("id", 999999)));
        assertThat(body(deliver("installation", unknown)).path("status").asString()).isEqualTo("IGNORED");
        assertThat(activeRepositoryCount()).isEqualTo(1);

        assertThat(deliver("installation", installationPayload("suspend")).statusCode()).isEqualTo(200);
        assertThat(installationStatus()).isEqualTo("SUSPENDED");
        assertThat(activeRepositoryCount()).isZero();
        assertThat(deliver("installation", installationPayload("unsuspend")).statusCode()).isEqualTo(200);
        assertThat(activeRepositoryCount()).isZero();
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        assertThat(activeRepositoryCount()).isEqualTo(2);
        assertThat(deliver("installation", installationPayload("deleted")).statusCode()).isEqualTo(200);
        assertThat(installationStatus()).isEqualTo("REMOVED");
        assertThat(activeRepositoryCount()).isZero();
        assertThat(body(deliver("installation", installationPayload("unsuspend"))).path("status").asString())
            .isEqualTo("IGNORED");
        assertThat(installationStatus()).isEqualTo("REMOVED");
    }

    @Test
    void upstreamRetryAndFailureNeverReplaceAValidSnapshotWithPartialData() throws Exception {
        GITHUB.transientFailures.set(1);
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        assertThat(GITHUB.metadataRequests).hasValue(2);
        GITHUB.partialResponse = true;
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(502);
        assertThat(activeRepositoryCount()).isEqualTo(2);
        GITHUB.partialResponse = false;
        GITHUB.rejectToken = true;
        var response = synchronize(owner, WORKSPACE_ID);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).doesNotContain(GitHubApiFixture.INSTALLATION_TOKEN, "PRIVATE KEY");
        assertThat(activeRepositoryCount()).isEqualTo(2);
        GITHUB.rejectToken = false;
        GITHUB.transientFailures.set(5);
        int before = GITHUB.metadataRequests.get();
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(502);
        assertThat(GITHUB.metadataRequests.get() - before).isEqualTo(3);
    }

    @Test
    void synchronizationCannotOverwriteARevocationReceivedWhileFetchingGitHub() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        GITHUB.pauseRepositories = true;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var synchronization = executor.submit(() -> synchronize(owner, WORKSPACE_ID));
            try {
                assertThat(GITHUB.snapshotRequested.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(deliver("installation", installationPayload("suspend")).statusCode()).isEqualTo(200);
            } finally {
                GITHUB.releaseSnapshot.countDown();
            }
            assertThat(synchronization.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }
        assertThat(installationStatus()).isEqualTo("SUSPENDED");
        assertThat(activeRepositoryCount()).isZero();
    }

    @Test
    void invalidOrOversizedWebhookRollsBackAndCanBeRedelivered() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        var bad = selectionPayload(List.of(Map.of("id", 20000, "full_name", "invalid-name")), List.of());
        var deliveryId = UUID.randomUUID().toString();
        assertThat(webhook("installation_repositories", deliveryId, bad, signature(bad)).statusCode()).isEqualTo(400);
        assertThat(count("webhook_deliveries")).isZero();
        var valid = selectionPayload(List.of(Map.of("id", 20000, "full_name", "acme/added")), List.of());
        assertThat(webhook("installation_repositories", deliveryId, valid, signature(valid)).statusCode()).isEqualTo(200);
        byte[] oversized = new byte[1024 * 1024 + 1];
        assertThat(webhook("ping", UUID.randomUUID().toString(), oversized, signature(oversized)).statusCode())
            .isEqualTo(413);
        assertThat(count("webhook_deliveries")).isEqualTo(1);
        assertThat(count("repositories")).isEqualTo(2);
    }

    @Test
    void databaseRejectsRepositoryLinksAcrossWorkspaces() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        var installation = jdbcTemplate.queryForObject("SELECT id FROM github_installations", UUID.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO repositories (id, workspace_id, installation_id, github_repository_id,
                full_name, is_active, created_at, updated_at)
            VALUES (?, ?, ?, 99999, 'acme/invalid-tenant', true, now(), now())
            """, UUID.randomUUID(), OTHER_WORKSPACE_ID, installation))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("repositories")).isEqualTo(2);
    }

    @Test
    void changingFromAllToSelectedWithoutRemovedIdsRequiresResynchronization() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        var payload = json(Map.of("action", "removed", "repository_selection", "selected",
            "installation", Map.of("id", GitHubApiFixture.INSTALLATION_ID),
            "repositories_added", List.of(), "repositories_removed", List.of()));
        assertThat(deliver("installation_repositories", payload).statusCode()).isEqualTo(200);
        assertThat(activeRepositoryCount()).isZero();
        GITHUB.repositoryCount = 1;
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        assertThat(activeRepositoryCount()).isEqualTo(1);
    }

    @Test
    void synchronizationObservesSuspensionReportedByTheApi() throws Exception {
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(200);
        GITHUB.suspended = true;
        GITHUB.rejectToken = true;
        var response = synchronize(owner, WORKSPACE_ID);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(activeRepositoryCount()).isZero();
    }

    @Test
    void upstreamBodySizeAndDeadlineAreBoundedAfterHeadersArrive() throws Exception {
        GITHUB.oversizedTokenBody = true;
        assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(502);
        assertThat(count("github_installations")).isZero();
        GITHUB.oversizedTokenBody = false;
        GITHUB.delayedTokenBody = true;
        var started = Instant.now();
        try {
            assertThat(synchronize(owner, WORKSPACE_ID).statusCode()).isEqualTo(502);
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(10));
        } finally {
            GITHUB.releaseSnapshot.countDown();
        }
        assertThat(count("github_installations")).isZero();
    }

    private Account createAccount() throws Exception {
        var email = UUID.randomUUID() + "@example.com";
        var password = "Phase02-fixture-password!";
        assertThat(call("POST", "/api/v1/auth/register", null,
            Map.of("email", email, "password", password, "displayName", "Developer")).statusCode()).isEqualTo(201);
        var response = call("POST", "/api/v1/auth/login", null, Map.of("email", email, "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        var result = body(response);
        return new Account(UUID.fromString(result.path("user").path("id").asString()),
            result.path("accessToken").asString());
    }

    private void insertWorkspace(UUID workspaceId, Account creator) {
        jdbcTemplate.update("""
            INSERT INTO workspaces (id, name, slug, created_by, created_at, updated_at)
            VALUES (?, 'GitHub fixture', ?, ?, now(), now())
            """, workspaceId, workspaceId.toString(), creator.id());
    }

    private void insertMember(UUID workspaceId, Account account, String role) {
        jdbcTemplate.update("""
            INSERT INTO workspace_members (id, workspace_id, user_id, role, status, joined_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', now())
            """, UUID.randomUUID(), workspaceId, account.id(), role);
    }

    private int count(String table) {
        // Only hard-coded table names from this test class, never HTTP input.
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int activeRepositoryCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM repositories WHERE is_active", Integer.class);
    }

    private String installationStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM github_installations", String.class);
    }

    private byte[] selectionPayload(Object added, Object removed) {
        return json(Map.of("action", "added", "installation", Map.of("id", GitHubApiFixture.INSTALLATION_ID),
            "repositories_added", added, "repositories_removed", removed));
    }

    private byte[] installationPayload(String action) {
        return json(Map.of("action", action, "installation", Map.of("id", GitHubApiFixture.INSTALLATION_ID)));
    }

    private HttpResponse<String> synchronize(Account account, UUID workspace) throws Exception {
        return call("POST", "/api/v1/workspaces/" + workspace + "/github/sync", account.accessToken(), null);
    }

    private HttpResponse<String> deliver(String event, byte[] payload) throws Exception {
        return webhook(event, UUID.randomUUID().toString(), payload, signature(payload));
    }

    private HttpResponse<String> webhook(String event, String id, byte[] payload, String signature) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/webhooks/github"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .header("X-GitHub-Event", event).header("X-GitHub-Delivery", id);
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return httpClient.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String signature(byte[] payload) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private HttpResponse<String> call(String method, String path, String accessToken, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(json(body));
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

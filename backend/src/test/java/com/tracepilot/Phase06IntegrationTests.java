package com.tracepilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.tracepilot.analysis.AnalysisRepository;
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
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
    properties = {"RAG_ENABLED=true", "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only", "AI_ENABLED=true",
        "AUTH_SIGNING_SECRET=phase05-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase06IntegrationTests {
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID OTHER_WORKSPACE_ID = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();
    private static final UUID REPOSITORY_ID = UUID.randomUUID();
    private static final GitHubApiFixture GITHUB = new GitHubApiFixture();
    private static final AiApiFixture AI = new AiApiFixture();
    private final ObjectMapper mapper = new ObjectMapper();
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
    AnalysisRepository analysisRepository;
    @Autowired
    com.tracepilot.knowledge.KnowledgeText knowledgeText;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    RabbitListenerEndpointRegistry listenerRegistry;
    @Value("${local.server.port}")
    int port;
    private Account owner;
    private Account member;
    private Account outsider;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("tracepilot.github.enabled", () -> true);
        registry.add("tracepilot.github.app-id", () -> GitHubApiFixture.APP_ID);
        registry.add("tracepilot.github.installation-id", () -> GitHubApiFixture.INSTALLATION_ID);
        registry.add("tracepilot.github.workspace-id", WORKSPACE_ID::toString);
        registry.add("tracepilot.github.private-key-path", () -> GITHUB.keyPath.toString());
        registry.add("tracepilot.github.webhook-secret", () -> "phase05-fixture-webhook-secret-not-for-production");
        registry.add("tracepilot.github.api-base-url", GITHUB::baseUrl);
        registry.add("tracepilot.pipeline.enabled", () -> true);
        registry.add("tracepilot.pipeline.retry-delay", () -> "100ms");
        registry.add("tracepilot.pipeline.dispatch-delay-ms", () -> 100);
        registry.add("tracepilot.ai.api-key", () -> AiApiFixture.KEY);
        registry.add("tracepilot.ai.model", () -> "fixture-model");
        registry.add("tracepilot.ai.base-url", AI::baseUrl);
        registry.add("tracepilot.ai.timeout", () -> "1s");
        registry.add("tracepilot.ai.max-calls-per-day", () -> 100);
        registry.add("tracepilot.ai.input-price-per-million", () -> "2");
        registry.add("tracepilot.ai.output-price-per-million", () -> "8");
        registry.add("tracepilot.ai.recovery-delay-ms", () -> 100);
        registry.add("tracepilot.rag.embedding-price-per-million", () -> "1.5");
        registry.add("tracepilot.notifications.poll-delay-ms", () -> 100);
    }

    @BeforeAll
    void accounts() throws Exception {
        owner = createAccount();
        member = createAccount();
        outsider = createAccount();
    }

    @BeforeEach
    void scope() {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbcTemplate.queryForObject("""
            SELECT count(*) FROM outbox_events WHERE processing_status IN ('PENDING', 'RUNNING', 'RETRY')
                OR status = 'PUBLISHING' OR (processing_status = 'FAILED' AND status <> 'PUBLISHED')
            """, Integer.class) == 0);
        for (var queue : List.of(PipelineQueues.SYNC_QUEUE, PipelineQueues.LOGS_QUEUE,
            PipelineQueues.AI_QUEUE, PipelineQueues.DEAD_QUEUE)) {
            rabbitAdmin.purgeQueue(queue, false);
        }
        GITHUB.reset();
        AI.reset();
        for (var table : List.of("outbox_events", "analysis_evidence", "ai_usage_events", "ai_analyses",
            "knowledge_chunks", "knowledge_articles", "notifications",
            "incident_log_snippets", "incidents", "workflow_jobs", "workflow_runs", "webhook_deliveries",
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
    void close() throws Exception {
        AI.close();
        GITHUB.close();
        httpClient.close();
    }

    @Test
    void onlyManagersApproveAndOnlyResolvedIncidentsCanBeUsedAsConfirmedFixes() throws Exception {
        var incidentId = incident();
        var invalid = call("POST", knowledgePath(WORKSPACE_ID), member.accessToken(),
            Map.of("sourceIncidentId", incidentId, "title", "Confirmed fix", "rootCause", "Investigated",
                "resolution", "Reviewed by developer"));
        assertThat(invalid.statusCode()).isEqualTo(409);
        var incidentPath = basePath() + "/incidents/" + incidentId + "/status";
        assertThat(call("PATCH", incidentPath, owner.accessToken(), Map.of("status", "INVESTIGATING", "version", 0)).statusCode()).isEqualTo(200);
        assertThat(call("PATCH", incidentPath, owner.accessToken(), Map.of("status", "RESOLVED", "version", 1)).statusCode()).isEqualTo(200);
        var created = call("POST", knowledgePath(WORKSPACE_ID), member.accessToken(),
            Map.of("sourceIncidentId", incidentId, "title", "Port conflict", "rootCause", "A socket is occupied.",
                "resolution", "Stop the stale process."));
        assertThat(created.statusCode()).isEqualTo(201);
        var id = UUID.fromString(body(created).path("id").asString());
        assertThat(call("POST", knowledgePath(WORKSPACE_ID) + "/" + id + "/approve", member.accessToken(),
            Map.of("version", 0)).statusCode()).isEqualTo(403);
        assertThat(call("GET", knowledgePath(WORKSPACE_ID) + "/" + id, outsider.accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(AI.embeddings.calls).hasValue(0);
        approve(owner, WORKSPACE_ID, id, 0);
        assertThat(article(owner, WORKSPACE_ID, id).path("indexStatus").asString()).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_usage_events WHERE operation = 'EMBEDDING'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void exactApproximateAndHybridSearchExcludeDraftArchivedAndOtherWorkspaceKnowledge() throws Exception {
        var approved = create(owner, WORKSPACE_ID, "Port socket fix", "The port is occupied.", "Stop stale listeners.");
        var draft = create(owner, WORKSPACE_ID, "Private draft port fix", "The port is occupied.", "DRAFT_ONLY_MARKER");
        var archived = create(owner, WORKSPACE_ID, "Old port fix", "The port is occupied.", "ARCHIVED_ONLY_MARKER");
        var foreign = create(outsider, OTHER_WORKSPACE_ID, "Other port fix", "The port is occupied.", "FOREIGN_ONLY_MARKER");
        approve(owner, WORKSPACE_ID, approved, 0);
        approve(owner, WORKSPACE_ID, archived, 0);
        approve(outsider, OTHER_WORKSPACE_ID, foreign, 0);
        assertThat(call("POST", knowledgePath(WORKSPACE_ID) + "/" + archived + "/archive", owner.accessToken(),
            Map.of("version", 2)).statusCode()).isEqualTo(200);
        for (var mode : List.of("EXACT", "APPROXIMATE", "HYBRID")) {
            var found = search("A socket address is already occupied.", mode, 5);
            assertThat(found.path("items").size()).isEqualTo(1);
            assertThat(found.path("items").get(0).path("articleId").asString()).isEqualTo(approved.toString());
            assertThat(found.toString()).doesNotContain(draft.toString(), "DRAFT_ONLY_MARKER", "ARCHIVED_ONLY_MARKER", "FOREIGN_ONLY_MARKER");
        }
    }

    @Test
    void editingAnApprovedArticleImmediatelyExcludesItsOldVectorsUntilTheNewRevisionIsIndexed() throws Exception {
        var id = create(owner, WORKSPACE_ID, "Port socket fix", "The port is occupied.", "Stop stale listeners.");
        approve(owner, WORKSPACE_ID, id, 0);
        var edited = call("PATCH", knowledgePath(WORKSPACE_ID) + "/" + id, owner.accessToken(),
            Map.of("version", 2, "title", "Java heap fix", "rootCause", "Memory is exhausted.",
                "resolution", "Inspect heap allocation."));
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(body(edited).path("status").asString()).isEqualTo("DRAFT");
        assertThat(search("A socket address is already occupied.", "EXACT", 5).path("items")).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE article_id = ?", Integer.class, id))
            .isEqualTo(1);
        assertThat(call("PATCH", knowledgePath(WORKSPACE_ID) + "/" + id, owner.accessToken(),
            Map.of("version", 2, "title", "stale", "rootCause", "stale", "resolution", "stale")).statusCode()).isEqualTo(409);
        approve(owner, WORKSPACE_ID, id, 3);
        assertThat(search("JVM memory is exhausted.", "EXACT", 1).path("items").get(0).path("articleVersion").asLong()).isEqualTo(1);
    }

    @Test
    void invalidEmbeddingBatchesNeverPublishPartialChunksAndCanBeRetried() throws Exception {
        var id = create(owner, WORKSPACE_ID, "Port socket fix", "The port is occupied.",
            "Inspect socket owners. ".repeat(100));
        AI.embeddings.duplicateIndex = true;
        var failed = call("POST", knowledgePath(WORKSPACE_ID) + "/" + id + "/approve", owner.accessToken(), Map.of("version", 0));
        assertThat(failed.statusCode()).isEqualTo(502);
        assertThat(article(owner, WORKSPACE_ID, id).path("indexStatus").asString()).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_chunks", Integer.class)).isZero();
        AI.embeddings.duplicateIndex = false;
        approve(owner, WORKSPACE_ID, id, 2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_chunks", Integer.class)).isGreaterThan(1);
        assertThat(jdbcTemplate.queryForList("SELECT vector_dims(embedding) FROM knowledge_chunks", Integer.class))
            .containsOnly(1536);
    }

    @Test
    void modelDimensionAndZeroVectorValidationFailClosed() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            var id = create(owner, WORKSPACE_ID, "Invalid vectors " + variant, "Socket test", "Inspect socket owners.");
            AI.embeddings.wrongDimension = variant == 0;
            AI.embeddings.wrongModel = variant == 1;
            AI.embeddings.zeroVector = variant == 2;
            assertThat(call("POST", knowledgePath(WORKSPACE_ID) + "/" + id + "/approve", owner.accessToken(),
                Map.of("version", 0)).statusCode()).isEqualTo(502);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_chunks", Integer.class)).isZero();
    }

    @Test
    void changingKnowledgeDuringEmbeddingPreventsPublishingTheStaleRevision() throws Exception {
        var id = create(owner, WORKSPACE_ID, "Socket fix", "The port is occupied.", "Stop stale listeners.");
        AI.embeddings.pause = true;
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> call("POST", knowledgePath(WORKSPACE_ID) + "/" + id + "/approve",
                owner.accessToken(), Map.of("version", 0)));
            try {
                assertThat(AI.embeddings.requested.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(search("Socket is occupied.", "EXACT", 5).path("items")).isEmpty();
                assertThat(call("PATCH", knowledgePath(WORKSPACE_ID) + "/" + id, owner.accessToken(),
                    Map.of("version", 1, "title", "Updated heap fix", "rootCause", "Memory exhausted",
                        "resolution", "Review allocations")).statusCode()).isEqualTo(200);
            } finally {
                AI.embeddings.release.countDown();
            }
            assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }
        assertThat(article(owner, WORKSPACE_ID, id).path("status").asString()).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_chunks", Integer.class)).isZero();
    }

    @Test
    void ragUsesOnlyApprovedWorkspaceKnowledgeAndSavesRealLogAndKnowledgeEvidence() throws Exception {
        var incidentId = incident();
        setLog(incidentId, "Error: EADDRINUSE socket could not bind port 8080");
        var approved = create(owner, WORKSPACE_ID, "Socket fix", "The port is occupied.", "Stop stale listeners before retrying.");
        create(owner, WORKSPACE_ID, "Draft socket", "The port is occupied.", "DRAFT_SECRET_MARKER");
        var foreign = create(outsider, OTHER_WORKSPACE_ID, "Foreign socket", "The port is occupied.", "FOREIGN_SECRET_MARKER");
        approve(owner, WORKSPACE_ID, approved, 0);
        approve(outsider, OTHER_WORKSPACE_ID, foreign, 0);
        AI.content = report("Error: EADDRINUSE socket could not bind port 8080", "Stop stale listeners before retrying.");
        var analysisId = request(owner, incidentId, UUID.randomUUID());
        waitFor(analysisId, "SUCCEEDED");
        assertThat(AI.inputs.getFirst()).contains("Stop stale listeners before retrying.")
            .doesNotContain("DRAFT_SECRET_MARKER", "FOREIGN_SECRET_MARKER");
        var evidence = call("GET", path(incidentId) + "/" + analysisId + "/evidence", member.accessToken(), null);
        assertThat(evidence.statusCode()).isEqualTo(200);
        assertThat(evidence.body()).contains("\"sourceType\":\"LOG\"", "\"sourceType\":\"KNOWLEDGE\"");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM analysis_evidence e JOIN knowledge_chunks k ON k.id = e.knowledge_chunk_id
            WHERE e.analysis_id = ? AND k.article_id = ?
            """, Integer.class, analysisId, approved)).isEqualTo(1);
        var usage = analysisRepository.usage(analysisId);
        assertThat(usage).hasSize(2);
        assertThat(usage.stream().filter(item -> item.operation().equals("EMBEDDING")).findFirst().orElseThrow().estimatedCostUsd())
            .isEqualByComparingTo("0.00015000");
        assertThat(usage.stream().filter(item -> item.operation().equals("CHAT")).findFirst().orElseThrow().inputTokens())
            .isEqualTo(120);
        assertThat(call("GET", path(incidentId) + "/" + analysisId + "/evidence", outsider.accessToken(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void archivedContextCannotBeCommittedAndRetryUsesFreshSources() throws Exception {
        var incidentId = incident();
        var log = "Error: EADDRINUSE socket could not bind port 8080";
        setLog(incidentId, log);
        var id = create(owner, WORKSPACE_ID, "Socket fix", "The port is occupied.", "Stop stale listeners before retrying.");
        approve(owner, WORKSPACE_ID, id, 0);
        AI.content = report("Stop stale listeners before retrying.");
        AI.pause = true;
        UUID analysisId;
        try {
            analysisId = request(owner, incidentId, UUID.randomUUID());
            assertThat(AI.requested.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call("POST", knowledgePath(WORKSPACE_ID) + "/" + id + "/archive", owner.accessToken(),
                Map.of("version", 2)).statusCode()).isEqualTo(200);
            AI.content = report(log);
        } finally {
            AI.release.countDown();
            AI.pause = false;
        }
        waitFor(analysisId, "SUCCEEDED");
        assertThat(AI.inputs).hasSize(2);
        assertThat(AI.inputs.get(1)).doesNotContain("Stop stale listeners before retrying.");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM analysis_evidence WHERE analysis_id = ? AND knowledge_chunk_id IS NOT NULL",
            Integer.class, analysisId)).isZero();
    }

    @Test
    void hybridSearchFindsAnExactErrorIdentifierWhenTheSemanticChannelHasNoMatch() throws Exception {
        var id = create(owner, WORKSPACE_ID, "Runner permission failure", "EACCES triggered ERR_XYZ_482.",
            "Grant the runner directory access.");
        approve(owner, WORKSPACE_ID, id, 0);
        assertThat(search("ERR_XYZ_482", "EXACT", 1).path("items")).isEmpty();
        assertThat(search("ERR_XYZ_482", "HYBRID", 1).path("items").get(0).path("articleId").asString()).isEqualTo(id.toString());
    }

    @Test
    void embeddingProviderFailureLeavesIncidentAndKnowledgeRecoverableWithoutLeakingErrorDetails() throws Exception {
        var id = create(owner, WORKSPACE_ID, "Socket fix", "The port is occupied.", "Stop stale listeners.");
        AI.embeddings.unavailable = true;
        var failure = call("POST", knowledgePath(WORKSPACE_ID) + "/" + id + "/approve", owner.accessToken(), Map.of("version", 0));
        assertThat(failure.statusCode()).isEqualTo(502);
        assertThat(failure.body()).doesNotContain("embedding-provider-private-detail", AiApiFixture.KEY);
        assertThat(article(owner, WORKSPACE_ID, id).path("indexStatus").asString()).isEqualTo("FAILED");
        AI.embeddings.unavailable = false;
        approve(owner, WORKSPACE_ID, id, 2);
        AI.embeddings.unavailable = true;
        var incidentId = incident();
        setLog(incidentId, "Error: EADDRINUSE socket could not bind port 8080");
        var analysisId = request(owner, incidentId, UUID.randomUUID());
        waitFor(analysisId, "FAILED");
        assertThat(AI.calls).hasValue(0);
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM analysis_evidence", Integer.class)).isZero();
    }

    @Test
    void databaseRejectsCrossWorkspaceChunksAndEvidenceWithoutASingleRealSource() throws Exception {
        var id = create(owner, WORKSPACE_ID, "Socket fix", "The port is occupied.", "Stop stale listeners.");
        approve(owner, WORKSPACE_ID, id, 0);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE knowledge_chunks SET workspace_id = ? WHERE article_id = ?",
            OTHER_WORKSPACE_ID, id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var incidentId = incident();
        var analysisId = request(owner, incidentId, UUID.randomUUID());
        waitFor(analysisId, "SUCCEEDED");
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO analysis_evidence (id, analysis_id, workspace_id, incident_id, quote, source_sha256, created_at)
            VALUES (?, ?, ?, ?, 'invented source', ?, now())
            """, UUID.randomUUID(), analysisId, WORKSPACE_ID, incidentId, "a".repeat(64)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void retrievalEvaluationMeasuresRecallAgainstTheTrackedParaphraseDataset() throws Exception {
        var resource = getClass().getResourceAsStream("/evaluation/rag-retrieval.json");
        assertThat(resource).isNotNull();
        tools.jackson.databind.JsonNode dataset;
        try (resource) {
            dataset = mapper.readTree(resource);
        }
        var expected = new java.util.HashMap<String, UUID>();
        for (var document : dataset.path("documents")) {
            var id = create(owner, WORKSPACE_ID, document.path("title").asString(),
                document.path("rootCause").asString(), document.path("resolution").asString());
            approve(owner, WORKSPACE_ID, id, 0);
            expected.put(document.path("key").asString(), id);
        }
        var metrics = new java.util.LinkedHashMap<String, Object>();
        for (var mode : List.of("EXACT", "APPROXIMATE", "HYBRID")) {
            int hits = 0;
            for (var query : dataset.path("queries")) {
                var result = search(query.path("query").asString(), mode, 1);
                if (!result.path("items").isEmpty()
                    && result.path("items").get(0).path("articleId").asString().equals(expected.get(query.path("key").asString()).toString())) {
                    hits++;
                }
            }
            double recall = (double) hits / dataset.path("queries").size();
            assertThat(recall).as(mode + " fixture Recall@1").isEqualTo(1.0);
            metrics.put(mode, Map.of("recallAt1", recall, "queries", dataset.path("queries").size()));
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "phase06-recall.json"),
            mapper.writeValueAsString(Map.of("scope", "synthetic embedding fixture; not a production quality score", "metrics", metrics)));
    }

    private String knowledgePath(UUID workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/knowledge-articles";
    }

    @Test
    void searchChecksMembershipAndValidationBeforeSpendingOnEmbeddings() throws Exception {
        var path = knowledgePath(WORKSPACE_ID) + "/search";
        var query = Map.of("query", "socket occupied", "topK", 3, "mode", "EXACT");
        assertThat(call("POST", path, outsider.accessToken(), query).statusCode()).isEqualTo(404);
        assertThat(call("POST", path, null, query).statusCode()).isEqualTo(401);
        assertThat(call("POST", path, owner.accessToken(),
            Map.of("query", "x".repeat(2001), "topK", 3, "mode", "EXACT")).statusCode()).isEqualTo(400);
        assertThat(call("POST", path, owner.accessToken(),
            Map.of("query", "socket", "topK", 0, "mode", "EXACT")).statusCode()).isEqualTo(400);
        assertThat(AI.embeddings.calls).hasValue(0);
    }

    @Test
    void providerReceivesOnlyRedactedKnowledgeAndPostgresHasAUsableCosineIndex() throws Exception {
        var id = create(owner, WORKSPACE_ID, "Socket fix", "The port is occupied.",
            "Stop stale listeners. " + AiApiFixture.KEY);
        approve(owner, WORKSPACE_ID, id, 0);
        assertThat(AI.embeddings.inputs.getFirst()).doesNotContain(AiApiFixture.KEY).contains("[REDACTED]");
        var vector = new float[1536];
        vector[0] = 1;
        var encoded = knowledgeText.vector(vector);
        var plan = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbcTemplate.queryForList("""
                EXPLAIN (COSTS OFF) SELECT id FROM knowledge_chunks
                ORDER BY embedding <=> CAST(? AS vector) LIMIT 3
                """, String.class, encoded));
        });
        assertThat(plan).contains("ix_knowledge_chunks_cosine");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "phase06-vector-plan.txt"),
            "Forced planner check on a small fixture; not a performance benchmark.\n" + plan);
    }

    private UUID create(Account actor, UUID workspaceId, String title, String root, String resolution) throws Exception {
        var response = call("POST", knowledgePath(workspaceId), actor.accessToken(),
            Map.of("title", title, "rootCause", root, "resolution", resolution));
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(body(response).path("id").asString());
    }

    private JsonNode article(Account actor, UUID workspaceId, UUID id) throws Exception {
        var response = call("GET", knowledgePath(workspaceId) + "/" + id, actor.accessToken(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private void approve(Account actor, UUID workspaceId, UUID id, long version) throws Exception {
        var response = call("POST", knowledgePath(workspaceId) + "/" + id + "/approve", actor.accessToken(), Map.of("version", version));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private JsonNode search(String query, String mode, int topK) throws Exception {
        var response = call("POST", knowledgePath(WORKSPACE_ID) + "/search", member.accessToken(),
            Map.of("query", query, "topK", topK, "mode", mode));
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private void setLog(UUID incidentId, String content) {
        jdbcTemplate.update("UPDATE incident_log_snippets SET content = ? WHERE incident_id = ?", content, incidentId);
    }

    private String report(String... quotes) {
        return mapper.writeValueAsString(Map.of("summary", "Investigated failure", "suspectedRootCause", "A possible cause requires confirmation",
            "suggestedFix", "Review the matching fix before applying it", "evidence", List.of(quotes)));
    }

    private UUID incident() throws Exception {
        sync();
        var id = jdbcTemplate.queryForObject("SELECT id FROM incidents", UUID.class);
        var jobId = jdbcTemplate.queryForObject("SELECT id FROM workflow_jobs ORDER BY github_job_id LIMIT 1", UUID.class);
        jdbcTemplate.update("UPDATE incidents SET failed_job_id = ? WHERE id = ?", jobId, id);
        jdbcTemplate.update("""
            INSERT INTO incident_log_snippets (id, incident_id, workflow_job_id, chunk_index, content,
                content_sha256, captured_at) VALUES (?, ?, ?, 0, ?, ?, now())
            """, UUID.randomUUID(), id, jobId, AiApiFixture.QUOTE + "\n" + AiApiFixture.KEY, "a".repeat(64));
        return id;
    }

    private void sync() throws Exception {
        var response = call("POST", basePath() + "/repositories/" + REPOSITORY_ID + "/workflow-runs/sync",
            owner.accessToken(), Map.of("githubRunId", 7000, "runAttempt", 1));
        assertThat(response.statusCode()).isEqualTo(202);
        var eventId = UUID.fromString(body(response).path("id").asString());
        await().atMost(Duration.ofSeconds(15)).until(() -> "SUCCEEDED".equals(outboxRepository.find(eventId).processingStatus()));
    }

    private UUID request(Account actor, UUID incidentId, UUID requestId) throws Exception {
        var response = call("POST", path(incidentId), actor.accessToken(), Map.of("requestId", requestId));
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(body(response).path("id").asString());
    }

    private void waitFor(UUID id, String status) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> assertThat(analysisRepository.find(id).orElseThrow().analysis().status()).isEqualTo(status));
    }

    private UUID eventId(UUID analysisId) {
        return jdbcTemplate.queryForObject("""
            SELECT id FROM outbox_events WHERE event_type = 'AI_ANALYSIS' AND payload->>'analysisId' = ?
            """, UUID.class, analysisId.toString());
    }

    private Account createAccount() throws Exception {
        var email = UUID.randomUUID() + "@example.com";
        var password = "Phase05-fixture-password!";
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
            VALUES (?, 'AI fixture', ?, ?, now(), now())
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

    private String basePath() {
        return "/api/v1/workspaces/" + WORKSPACE_ID;
    }

    private String path(UUID incidentId) {
        return basePath() + "/incidents/" + incidentId + "/analyses";
    }

    private HttpResponse<String> call(String method, String path, String token, Object value) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        var publisher = value == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(value));
        return httpClient.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private record Account(UUID id, String accessToken) {
    }
}

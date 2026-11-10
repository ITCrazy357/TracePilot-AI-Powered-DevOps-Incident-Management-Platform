package com.tracepilot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"RATE_LIMIT_ENABLED=false", "RAG_ENABLED=false", "AI_ENABLED=false", "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only", "GITHUB_ENABLED=false",
        "AUTH_SIGNING_SECRET=phase01-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class TracePilotApplicationTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Value("${local.server.port}")
    int port;

    @Test
    void healthChecksRealPostgresAndRabbitMq() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .timeout(java.time.Duration.ofSeconds(15)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void flywayMigratesFreshDatabaseThroughPhase08() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success", Integer.class))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success", Integer.class))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success", Integer.class))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name IN
            ('users', 'refresh_tokens', 'workspaces', 'workspace_members',
             'github_installations', 'repositories', 'webhook_deliveries',
             'workflow_runs', 'workflow_jobs', 'outbox_events', 'incidents', 'incident_log_snippets', 'notifications')
            """, String.class)).containsExactlyInAnyOrder(
            "users", "refresh_tokens", "workspaces", "workspace_members",
            "github_installations", "repositories", "webhook_deliveries",
            "workflow_runs", "workflow_jobs", "outbox_events", "incidents", "incident_log_snippets", "notifications");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name IN ('ai_analyses', 'ai_usage_events')
            """, String.class)).containsExactlyInAnyOrder("ai_analyses", "ai_usage_events");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
            SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
                AND table_name IN ('knowledge_articles', 'knowledge_chunks', 'analysis_evidence')
            """, String.class)).containsExactlyInAnyOrder("knowledge_articles", "knowledge_chunks", "analysis_evidence");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
            SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
                AND table_name IN ('audit_logs', 'rate_limit_buckets')
            """, String.class)).containsExactlyInAnyOrder("audit_logs", "rate_limit_buckets");
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('public.incident_feedback')::text", String.class))
            .isEqualTo("incident_feedback");
    }

    @Test
    void pgvectorSupportsCosineDistance() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT '[1,0,0]'::vector <=> '[1,0,0]'::vector", Double.class)).isZero();
    }

    @Test
    void disabledGitHubIntegrationRejectsWebhookWithoutWritingMetadata() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/webhooks/github"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM webhook_deliveries", Integer.class)).isZero();
    }

    @Test
    void upgradingPhase10PreservesApprovedKnowledgeAndHistoricalAnalyses() {
        var schema = "phase10_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("11").load().migrate();
        var userId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var repositoryId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var incidentId = UUID.randomUUID();
        var analysisId = UUID.randomUUID();
        var articleId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO %s.users(id,email,password_hash,display_name,created_at,updated_at)
            VALUES (?, 'legacy@example.test', 'fixture-only', 'Legacy', now(), now())
            """.formatted(schema), userId);
        jdbcTemplate.update("""
            INSERT INTO %s.workspaces(id,name,slug,created_by,created_at,updated_at)
            VALUES (?, 'Legacy', 'legacy', ?, now(), now())
            """.formatted(schema), workspaceId, userId);
        jdbcTemplate.update("""
            INSERT INTO %s.github_installations(id,workspace_id,github_installation_id,account_login,status,created_at,updated_at)
            VALUES (?, ?, 1, 'fixture', 'ACTIVE', now(), now())
            """.formatted(schema), installationId, workspaceId);
        jdbcTemplate.update("""
            INSERT INTO %s.repositories(id,workspace_id,installation_id,github_repository_id,full_name,is_active,created_at,updated_at)
            VALUES (?, ?, ?, 1, 'fixture/legacy', true, now(), now())
            """.formatted(schema), repositoryId, workspaceId, installationId);
        jdbcTemplate.update("""
            INSERT INTO %s.workflow_runs(id,repository_id,github_run_id,run_attempt,github_workflow_id,workflow_name,
                status,conclusion,source_updated_at,created_at,updated_at)
            VALUES (?, ?, 1, 1, 1, 'Legacy workflow', 'completed', 'failure', now(), now(), now())
            """.formatted(schema), runId, repositoryId);
        jdbcTemplate.update("""
            INSERT INTO %s.incidents(id,workspace_id,repository_id,workflow_run_id,title,detected_at,created_at,updated_at)
            VALUES (?, ?, ?, ?, 'Legacy incident', now(), now(), now())
            """.formatted(schema), incidentId, workspaceId, repositoryId, runId);
        jdbcTemplate.update("""
            INSERT INTO %s.ai_analyses(id,workspace_id,incident_id,request_id,origin,provider,model,prompt_version,
                input_sha256,status,summary,suspected_root_cause,suggested_fix,completed_at,created_at)
            VALUES (?, ?, ?, ?, 'MANUAL', 'OPENAI', 'legacy-model', 'incident-rca-v2', ?, 'SUCCEEDED',
                'Legacy summary', 'Legacy cause', 'Legacy fix', now(), now())
            """.formatted(schema), analysisId, workspaceId, incidentId, UUID.randomUUID(), "a".repeat(64));
        jdbcTemplate.update("""
            INSERT INTO %s.knowledge_articles(id,workspace_id,title,root_cause,resolution,status,index_status,
                created_by,approved_by,approved_at,created_at,updated_at)
            VALUES (?, ?, 'Legacy article', 'Legacy verified cause', 'Legacy verified fix', 'APPROVED', 'READY',
                ?, ?, now(), now(), now())
            """.formatted(schema), articleId, workspaceId, userId, userId);
        jdbcTemplate.update("""
            INSERT INTO %s.knowledge_chunks(id,workspace_id,article_id,article_version,chunk_index,content,
                content_sha256,embedding,embedding_model,created_at)
            VALUES (?, ?, ?, 0, 0, 'Legacy verified fix', ?, CAST(? AS vector), 'text-embedding-3-small', now())
            """.formatted(schema), UUID.randomUUID(), workspaceId, articleId, "a".repeat(64), "[1," + "0,".repeat(1534) + "0]");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
        var analysis = jdbcTemplate.queryForMap("SELECT * FROM " + schema + ".ai_analyses WHERE id = ?", analysisId);
        assertThat(analysis.get("summary")).isEqualTo("Legacy summary");
        assertThat(analysis.get("status")).isEqualTo("SUCCEEDED");
        assertThat(analysis.get("run_context")).isNull();
        assertThat(analysis.get("assessment_status")).isNull();
        assertThat(analysis.get("verification_steps").toString()).isEqualTo("[]");
        var article = jdbcTemplate.queryForMap("SELECT * FROM " + schema + ".knowledge_articles WHERE id = ?", articleId);
        assertThat(article.get("status")).isEqualTo("APPROVED");
        assertThat(article.get("index_status")).isEqualTo("READY");
        assertThat(article.get("root_cause")).isEqualTo("Legacy verified cause");
        assertThat(article.get("verification")).isEqualTo("");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + schema + ".knowledge_chunks WHERE article_id = ?",
            Integer.class, articleId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + schema
            + ".flyway_schema_history WHERE version IN ('12','13','14') AND success", Integer.class)).isEqualTo(3);
    }

    @Test
    void upgradesAnExistingPhase00SchemaWithoutResettingItsHistory() {
        // A separate schema in the disposable test database; local volumes are untouched.
        var schema = "phase00_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .target("1").load().migrate();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version = '1' AND success",
            Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'users'",
            Integer.class, schema)).isZero();
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("4").load().migrate();
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("5").load().migrate();
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("6").load().migrate();
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version IN ('1', '2', '3', '4', '5', '6', '7', '8', '9') AND success",
            Integer.class)).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'users'",
            Integer.class, schema)).isEqualTo(1);
    }

}

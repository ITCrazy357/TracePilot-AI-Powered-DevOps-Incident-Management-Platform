package com.tracepilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only", "GITHUB_ENABLED=false",
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
    void flywayMigratesFreshDatabaseThroughPhase03() {
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
                 'workflow_runs', 'workflow_jobs', 'outbox_events')
                """, String.class)).containsExactlyInAnyOrder(
                "users", "refresh_tokens", "workspaces", "workspace_members",
                "github_installations", "repositories", "webhook_deliveries",
                "workflow_runs", "workflow_jobs", "outbox_events");
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('public.incidents')::text", String.class)).isNull();
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
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version IN ('1', '2', '3', '4') AND success",
                Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'users'",
                Integer.class, schema)).isEqualTo(1);
    }

}

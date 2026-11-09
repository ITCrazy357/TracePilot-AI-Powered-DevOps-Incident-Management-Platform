package com.tracepilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"RATE_LIMIT_ENABLED=false", "RAG_ENABLED=false", "AI_ENABLED=false", "GITHUB_ENABLED=false",
        "PIPELINE_ENABLED=false", "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only",
        "AUTH_SIGNING_SECRET=phase10-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase10IntegrationTests {
    @Autowired
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

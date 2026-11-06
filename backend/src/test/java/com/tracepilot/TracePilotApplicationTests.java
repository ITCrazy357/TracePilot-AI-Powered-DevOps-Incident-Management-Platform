package com.tracepilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only" })
class TracePilotApplicationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

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
	void flywayMigratesFreshDatabaseWithoutCreatingBusinessTables() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class))
				.isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('public.users')::text", String.class)).isNull();
	}

	@Test
	void pgvectorSupportsCosineDistance() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT '[1,0,0]'::vector <=> '[1,0,0]'::vector", Double.class)).isZero();
	}

}

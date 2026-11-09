package com.tracepilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

import com.tracepilot.identity.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"RATE_LIMIT_ENABLED=false", "RAG_ENABLED=false", "AI_ENABLED=false", "POSTGRES_PASSWORD=test-only", "RABBITMQ_PASSWORD=test-only", "GITHUB_ENABLED=false",
        "AUTH_SIGNING_SECRET=phase01-test-only-secret-with-at-least-sixty-four-characters-1234567890"})
class Phase01IntegrationTests {
    private static final String PASSWORD = "Phase01-password-123!";
    private final ObjectMapper json = new ObjectMapper();
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    JwtEncoder encoder;
    @Value("${local.server.port}")
    int port;

    @Test
    void registrationNormalizesEmailAndHashesPassword() throws Exception {
        var email = "MixedCase-" + UUID.randomUUID() + "@example.com";
        var response = call("POST", "/auth/register", null,
            Map.of("email", email, "password", PASSWORD, "displayName", " Developer "));
        assertThat(response.statusCode()).isEqualTo(201);
        var body = body(response);
        assertThat(body.path("email").asString()).isEqualTo(email.toLowerCase(Locale.ROOT));
        assertThat(body.path("displayName").asString()).isEqualTo("Developer");
        assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", "password_hash");
        var hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?",
            String.class, UUID.fromString(body.path("id").asString()));
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(call("POST", "/auth/register", null,
            Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD, "displayName", "Other"))
            .statusCode()).isEqualTo(409);
    }

    @Test
    void loginMeAndInvalidCredentials() throws Exception {
        var account = createAccount();
        var me = call("GET", "/auth/me", account.access(), null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(body(me).path("id").asString()).isEqualTo(account.id());
        assertThat(call("GET", "/auth/me", null, null).statusCode()).isEqualTo(401);
        var badPassword = call("POST", "/auth/login", null,
            Map.of("email", account.email(), "password", "wrong-password"));
        var missingUser = call("POST", "/auth/login", null,
            Map.of("email", UUID.randomUUID() + "@example.com", "password", "wrong-password"));
        assertThat(badPassword.statusCode()).isEqualTo(401);
        assertThat(missingUser.statusCode()).isEqualTo(401);
        assertThat(body(badPassword).path("detail").asString())
            .isEqualTo(body(missingUser).path("detail").asString());
        assertThat(call("GET", "/workspaces", account.refresh(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshRotatesAndLogoutRevokesOnlyTheSelectedSession() throws Exception {
        var account = createAccount();
        var hash = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE user_id = ?",
            String.class, UUID.fromString(account.id()));
        assertThat(hash).hasSize(64).isEqualTo(TokenService.hash(account.refresh()));
        assertThat(hash).isNotEqualTo(account.refresh());
        var response = call("POST", "/auth/refresh", null, Map.of("refreshToken", account.refresh()));
        assertThat(response.statusCode()).isEqualTo(200);
        var next = body(response).path("refreshToken").asString();
        assertThat(next).isNotEqualTo(account.refresh());
        assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", account.refresh()))
            .statusCode()).isEqualTo(401);
        assertThat(call("GET", "/auth/me", body(response).path("accessToken").asString(), null)
            .statusCode()).isEqualTo(200);
        var anotherLogin = call("POST", "/auth/login", null,
            Map.of("email", account.email(), "password", PASSWORD));
        var independent = body(anotherLogin).path("refreshToken").asString();
        assertThat(call("POST", "/auth/logout", null, Map.of("refreshToken", next)).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/auth/logout", null, Map.of("refreshToken", next)).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", next)).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", independent))
            .statusCode()).isEqualTo(200);
    }

    @Test
    void expiredAndUnknownRefreshTokensAreRejected() throws Exception {
        var account = createAccount();
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = ?",
            UUID.fromString(account.id()));
        assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", account.refresh()))
            .statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", "A".repeat(43)))
            .statusCode()).isEqualTo(401);
    }

    @Test
    void concurrentRefreshCanConsumeTheTokenOnlyOnce() throws Exception {
        var account = createAccount();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return call("POST", "/auth/refresh", null, Map.of("refreshToken", account.refresh())).statusCode();
            });
            var second = executor.submit(() -> {
                start.await();
                return call("POST", "/auth/refresh", null, Map.of("refreshToken", account.refresh())).statusCode();
            });
            start.countDown();
            assertThat(List.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                second.get(30, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 401);
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL
            """, Integer.class, UUID.fromString(account.id()))).isEqualTo(1);
    }

    @Test
    void accessTokenSignatureExpiryIssuerAudienceAndTypeAreValidated() throws Exception {
        var account = createAccount();
        var now = Instant.now();
        var validClaims = JwtClaimsSet.builder().subject(account.id()).issuer(TokenService.ISSUER)
            .audience(List.of(TokenService.AUDIENCE)).issuedAt(now).expiresAt(now.plusSeconds(300))
            .claim("token_use", "access").build();
        var token = signed(validClaims);
        var parts = token.split("\\.");
        var signature = parts[2];
        var tampered = parts[0] + "." + parts[1] + "." + (signature.charAt(0) == 'A' ? 'B' : 'A') + signature.substring(1);
        assertThat(call("GET", "/auth/me", tampered, null).statusCode()).isEqualTo(401);
        var invalidClaims = List.of(
            JwtClaimsSet.from(validClaims).issuedAt(now.minusSeconds(300)).expiresAt(now.minusSeconds(1)).build(),
            JwtClaimsSet.from(validClaims).issuer("another-service").build(),
            JwtClaimsSet.from(validClaims).audience(List.of("another-api")).build(),
            JwtClaimsSet.from(validClaims).claim("token_use", "refresh").build(),
            JwtClaimsSet.from(validClaims).subject("not-a-uuid").build(),
            JwtClaimsSet.from(validClaims).claims(claims -> claims.remove("exp")).build());
        for (var claims : invalidClaims) {
            assertThat(call("GET", "/auth/me", signed(claims), null).statusCode()).isEqualTo(401);
        }
        assertThat(call("GET", "/auth/me", token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void workspaceCreationIsAtomicAndReadsAreTenantScoped() throws Exception {
        var owner = createAccount();
        var outsider = createAccount();
        var workspace = createWorkspace(owner);
        var another = createWorkspace(outsider);
        assertThat(call("GET", "/workspaces/" + workspace, owner.access(), null).statusCode()).isEqualTo(200);
        var list = call("GET", "/workspaces", owner.access(), null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(workspace).doesNotContain(another);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM workspace_members
            WHERE workspace_id = ? AND user_id = ? AND role = 'OWNER' AND status = 'ACTIVE'
            """, Integer.class, UUID.fromString(workspace), UUID.fromString(owner.id()))).isEqualTo(1);
        for (var suffix : List.of("", "/members")) {
            assertThat(call("GET", "/workspaces/" + workspace + suffix, outsider.access(), null)
                .statusCode()).isEqualTo(404);
        }
        assertThat(call("POST", "/workspaces/" + workspace + "/members", outsider.access(),
            Map.of("userId", outsider.id(), "role", "OWNER")).statusCode()).isEqualTo(404);
        var original = body(call("GET", "/workspaces/" + workspace, owner.access(), null));
        assertThat(call("POST", "/workspaces", outsider.access(),
            Map.of("name", "Duplicate", "slug", original.path("slug").asString())).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE name = 'Duplicate'",
            Integer.class)).isZero();
    }

    @Test
    void memberCannotPromoteThemselfOrManageOthers() throws Exception {
        var owner = createAccount();
        var member = createAccount();
        var workspace = createWorkspace(owner);
        assertThat(addMember(owner, workspace, member, "MEMBER").statusCode()).isEqualTo(201);
        assertThat(call("GET", "/workspaces/" + workspace, member.access(), null).statusCode()).isEqualTo(200);
        for (var desired : List.of("OWNER", "ADMIN", "MEMBER")) {
            assertThat(changeRole(member, workspace, member, desired).statusCode()).isEqualTo(403);
        }
        assertThat(addMember(member, workspace, owner, "MEMBER").statusCode()).isEqualTo(403);
        assertThat(removeMember(member, workspace, owner).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
            String.class, UUID.fromString(workspace), UUID.fromString(member.id()))).isEqualTo("MEMBER");
    }

    @Test
    void adminCanManageOnlyMembersAndOwnerCannotBeRemoved() throws Exception {
        var owner = createAccount();
        var admin = createAccount();
        var member = createAccount();
        var workspace = createWorkspace(owner);
        assertThat(addMember(owner, workspace, admin, "ADMIN").statusCode()).isEqualTo(201);
        assertThat(addMember(admin, workspace, member, "ADMIN").statusCode()).isEqualTo(403);
        assertThat(addMember(admin, workspace, member, "OWNER").statusCode()).isEqualTo(403);
        assertThat(addMember(admin, workspace, member, "MEMBER").statusCode()).isEqualTo(201);
        assertThat(addMember(owner, workspace, member, "MEMBER").statusCode()).isEqualTo(409);
        assertThat(changeRole(admin, workspace, admin, "OWNER").statusCode()).isEqualTo(403);
        assertThat(changeRole(admin, workspace, admin, "MEMBER").statusCode()).isEqualTo(403);
        assertThat(removeMember(admin, workspace, admin).statusCode()).isEqualTo(403);
        assertThat(changeRole(admin, workspace, member, "ADMIN").statusCode()).isEqualTo(403);
        assertThat(changeRole(owner, workspace, member, "OWNER").statusCode()).isEqualTo(403);
        assertThat(removeMember(admin, workspace, owner).statusCode()).isEqualTo(403);
        assertThat(removeMember(owner, workspace, owner).statusCode()).isEqualTo(403);
        assertThat(changeRole(owner, workspace, owner, "MEMBER").statusCode()).isEqualTo(403);
        assertThat(removeMember(admin, workspace, member).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/workspaces/" + workspace, member.access(), null).statusCode()).isEqualTo(404);
        assertThat(body(call("GET", "/workspaces", member.access(), null)).isEmpty()).isTrue();
        assertThat(addMember(owner, workspace, member, "MEMBER").statusCode()).isEqualTo(201);
        assertThat(changeRole(owner, workspace, member, "ADMIN").statusCode()).isEqualTo(200);
        assertThat(changeRole(owner, workspace, admin, "MEMBER").statusCode()).isEqualTo(200);
        // The same access token immediately loses administration rights after demotion.
        assertThat(addMember(admin, workspace, owner, "MEMBER").statusCode()).isEqualTo(403);
    }

    @Test
    void invalidInputFailsBeforePersistence() throws Exception {
        for (var invalid : List.of(
            Map.of("email", "bad-email", "password", PASSWORD, "displayName", "Developer"),
            Map.of("email", "valid@example.com", "password", "short", "displayName", "Developer"),
            Map.of("email", "valid@example.com", "password", "界".repeat(25), "displayName", "Developer"),
            Map.of("email", "valid@example.com", "password", PASSWORD, "displayName", " "))) {
            var response = call("POST", "/auth/register", null, invalid);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain(PASSWORD, "界".repeat(25));
        }
        var owner = createAccount();
        assertThat(call("POST", "/workspaces", owner.access(), Map.of("name", "Demo", "slug", "Bad Slug!"))
            .statusCode()).isEqualTo(400);
        assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", "short"))
            .statusCode()).isEqualTo(400);
        assertThat(call("POST", "/auth/login", null, Map.of()).statusCode()).isEqualTo(400);
    }

    private Account createAccount() throws Exception {
        var email = UUID.randomUUID() + "@example.com";
        var registration = call("POST", "/auth/register", null,
            Map.of("email", email, "password", PASSWORD, "displayName", "Developer"));
        assertThat(registration.statusCode()).isEqualTo(201);
        var login = call("POST", "/auth/login", null, Map.of("email", email, "password", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("cache-control").orElse("")).contains("no-store");
        assertThat(login.headers().allValues("set-cookie")).isEmpty();
        var data = body(login);
        assertThat(data.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(data.path("expiresIn").asLong()).isEqualTo(900);
        return new Account(data.path("user").path("id").asString(), email,
            data.path("accessToken").asString(), data.path("refreshToken").asString());
    }

    private String createWorkspace(Account owner) throws Exception {
        var response = call("POST", "/workspaces", owner.access(),
            Map.of("name", "Demo", "slug", "demo-" + UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(201);
        return body(response).path("id").asString();
    }

    private HttpResponse<String> addMember(Account actor, String workspace, Account target, String role) throws Exception {
        return call("POST", "/workspaces/" + workspace + "/members", actor.access(),
            Map.of("userId", target.id(), "role", role));
    }

    private HttpResponse<String> changeRole(Account actor, String workspace, Account target, String role) throws Exception {
        return call("PATCH", "/workspaces/" + workspace + "/members/" + target.id(), actor.access(), Map.of("role", role));
    }

    private HttpResponse<String> removeMember(Account actor, String workspace, Account target) throws Exception {
        return call("DELETE", "/workspaces/" + workspace + "/members/" + target.id(), actor.access(), null);
    }

    private String signed(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private HttpResponse<String> call(String method, String path, String access, Object data) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
            if (access != null) {
                request.header("Authorization", "Bearer " + access);
            }
            var publisher = data == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data));
            if (data != null) {
                request.header("Content-Type", "application/json");
            }
            return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private record Account(String id, String email, String access, String refresh) {
    }
}

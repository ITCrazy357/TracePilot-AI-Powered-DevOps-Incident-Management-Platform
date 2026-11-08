package com.tracepilot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

final class GitHubApiFixture implements AutoCloseable {
    static final long APP_ID = 42;
    static final long INSTALLATION_ID = 501;
    static final String INSTALLATION_TOKEN = "fixture-installation-token-never-store-this";
    final AtomicInteger metadataRequests = new AtomicInteger();
    final AtomicInteger transientFailures = new AtomicInteger();
    final WorkflowApiFixture workflows = new WorkflowApiFixture();
    final JobLogApiFixture logs = new JobLogApiFixture();
    volatile int repositoryCount = 2;
    volatile boolean partialResponse;
    volatile boolean rejectToken;
    volatile boolean pauseRepositories;
    volatile boolean suspended;
    volatile boolean oversizedTokenBody;
    volatile boolean delayedTokenBody;
    volatile CountDownLatch snapshotRequested = new CountDownLatch(1);
    volatile CountDownLatch releaseSnapshot = new CountDownLatch(1);
    final Path keyPath;
    private final KeyPair keyPair;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    GitHubApiFixture() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
            keyPath = Files.createTempFile("tracepilot-github-fixture-", ".pem");
            var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(keyPath, pem);
            keyPath.toFile().deleteOnExit();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot start GitHub API fixture.", exception);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        workflows.reset();
        logs.reset();
        metadataRequests.set(0);
        transientFailures.set(0);
        repositoryCount = 2;
        partialResponse = false;
        rejectToken = false;
        pauseRepositories = false;
        suspended = false;
        oversizedTokenBody = false;
        delayedTokenBody = false;
        snapshotRequested = new CountDownLatch(1);
        releaseSnapshot = new CountDownLatch(1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var path = exchange.getRequestURI().getPath();
            if (path.equals("/download/job.log")) {
                logs.download(exchange);
                return;
            }
            if (!"2026-03-10".equals(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"))) {
                respond(exchange, 400, Map.of("message", "Missing API version"));
                return;
            }
            if (path.startsWith("/repos/")) {
                if (!("Bearer " + INSTALLATION_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 401, Map.of("message", "Invalid installation token"));
                    return;
                }
                if (path.matches("/repos/acme/repo-0/actions/jobs/[0-9]+/logs")) {
                    logs.redirect(exchange, baseUrl());
                } else {
                    workflows.handle(exchange);
                }
                return;
            }
            if (path.startsWith("/app/installations/")) {
                if (!validAppToken(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 401, Map.of("message", "Invalid app JWT"));
                    return;
                }
                if (path.equals("/app/installations/" + INSTALLATION_ID)) {
                    metadataRequests.incrementAndGet();
                    if (transientFailures.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                        respond(exchange, 503, Map.of("message", "Upstream private failure detail"));
                        return;
                    }
                    var suspendedAt = suspended ? "\"2026-11-08T00:00:00Z\"" : "null";
                    var metadata = "{\"id\":501,\"app_id\":42,\"account\":{\"login\":\"acme\"},\"suspended_at\":"
                        + suspendedAt + "}";
                    respondJson(exchange, 200, metadata);
                    return;
                }
                if (path.endsWith("/access_tokens") && "POST".equals(exchange.getRequestMethod())) {
                    var permissions = objectMapper.readTree(exchange.getRequestBody()).path("permissions");
                    if (permissions.size() != 2 || !"read".equals(permissions.path("metadata").asString())
                        || !"read".equals(permissions.path("actions").asString())) {
                        respond(exchange, 400, Map.of("message", "Token must request only Metadata/Actions read"));
                        return;
                    }
                    if (delayedTokenBody) {
                        respondWithDelayedBody(exchange);
                    } else {
                        var token = oversizedTokenBody ? "x".repeat(4 * 1024 * 1024) : INSTALLATION_TOKEN;
                        respond(exchange, rejectToken ? 403 : 201, Map.of("token", token));
                    }
                    return;
                }
            }
            if (path.equals("/installation/repositories")) {
                if (!("Bearer " + INSTALLATION_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 401, Map.of("message", "Invalid installation token"));
                    return;
                }
                var body = repositoryPage(exchange.getRequestURI().getQuery());
                if (pauseRepositories) {
                    snapshotRequested.countDown();
                    if (!releaseSnapshot.await(10, TimeUnit.SECONDS)) {
                        respond(exchange, 504, Map.of("message", "Fixture synchronization timed out"));
                        return;
                    }
                }
                respond(exchange, 200, body);
                return;
            }
            respond(exchange, 404, Map.of("message", "Unknown fixture endpoint"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> repositoryPage(String query) {
        int page = Integer.parseInt(query.substring(query.indexOf("&page=") + "&page=".length()));
        var repositories = new ArrayList<Map<String, Object>>();
        for (int index = (page - 1) * 100; index < Math.min(page * 100, repositoryCount); index++) {
            repositories.add(Map.of("id", 10000 + index, "full_name", "acme/repo-" + index,
                "default_branch", "main"));
        }
        return Map.of("total_count", repositoryCount + (partialResponse ? 1 : 0), "repositories", repositories);
    }

    private boolean validAppToken(String authorization) {
        try {
            var token = SignedJWT.parse(authorization.substring("Bearer ".length()));
            var claims = token.getJWTClaimsSet();
            return token.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))
                && "RS256".equals(token.getHeader().getAlgorithm().getName())
                && Long.toString(APP_ID).equals(claims.getIssuer())
                && claims.getExpirationTime().after(new java.util.Date());
        } catch (Exception exception) {
            return false;
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        respondJson(exchange, status, objectMapper.writeValueAsString(body));
    }

    private void respondWithDelayedBody(HttpExchange exchange) throws IOException, InterruptedException {
        var bytes = objectMapper.writeValueAsBytes(Map.of("token", INSTALLATION_TOKEN));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, bytes.length);
        exchange.getResponseBody().write(bytes, 0, 1);
        exchange.getResponseBody().flush();
        releaseSnapshot.await(15, TimeUnit.SECONDS);
        exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
    }

    private void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() throws IOException {
        workflows.releaseSnapshot.countDown();
        releaseSnapshot.countDown();
        server.stop(0);
        executor.shutdownNow();
        Files.deleteIfExists(keyPath);
    }
}

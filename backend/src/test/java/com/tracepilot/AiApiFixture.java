package com.tracepilot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

final class AiApiFixture implements AutoCloseable {
    static final String KEY = "fixture-ai-opaque-key-never-store-this";
    static final String QUOTE = "Error: expected HTTP 200 but got 500";
    final AtomicInteger calls = new AtomicInteger();
    final EmbeddingApiFixture embeddings = new EmbeddingApiFixture();
    final List<String> problems = new CopyOnWriteArrayList<>();
    final List<String> inputs = new CopyOnWriteArrayList<>();
    volatile int status;
    volatile String content;
    volatile String finishReason;
    volatile boolean pause;
    volatile boolean oversized;
    volatile boolean omitUsage;
    volatile CountDownLatch requested;
    volatile CountDownLatch release;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    AiApiFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            reset();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start AI fixture.", exception);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    void reset() {
        embeddings.reset();
        calls.set(0);
        problems.clear();
        inputs.clear();
        status = 200;
        finishReason = "stop";
        pause = false;
        oversized = false;
        omitUsage = false;
        requested = new CountDownLatch(1);
        release = new CountDownLatch(1);
        content = mapper.writeValueAsString(Map.of("summary", "Job kiểm thử thất bại",
            "suspectedRootCause", "Có thể ứng dụng trả HTTP 500; cần kiểm tra server.",
            "suggestedFix", "Kiểm tra lỗi server rồi chạy lại test; cần con người xác nhận.",
            "evidence", List.of(QUOTE)));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if ("/v1/embeddings".equals(exchange.getRequestURI().getPath())) {
                embeddings.handle(exchange);
                return;
            }
            calls.incrementAndGet();
            if (!"/v1/chat/completions".equals(exchange.getRequestURI().getPath())
                || !"POST".equals(exchange.getRequestMethod())
                || !("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                problems.add("Unexpected endpoint or credentials");
            }
            var request = mapper.readTree(exchange.getRequestBody());
            var messages = request.path("messages");
            if (request.path("tools").isMissingNode()
                && (!request.path("tool_choice").isMissingNode()
                    || !request.path("parallel_tool_calls").isMissingNode())) {
                problems.add("Tool options require a tools array");
                respond(exchange, 400, mapper.writeValueAsString(Map.of("error", Map.of(
                    "message", "Tool options require a tools array", "type", "invalid_request_error"))));
                return;
            }
            if (!request.path("model").asString().equals("fixture-model")
                || !request.path("response_format").path("json_schema").path("strict").asBoolean()
                || request.path("store").asBoolean() || !request.path("tools").isMissingNode()
                || request.path("max_completion_tokens").asInt() > 2048 || messages.size() != 2
                || !"system".equals(messages.get(0).path("role").asString())
                || !"user".equals(messages.get(1).path("role").asString())) {
                problems.add("Unsafe or unexpected request options");
            }
            inputs.add(messages.get(1).path("content").asString());
            if (status == 302) {
                exchange.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (status != 200) {
                respond(exchange, status, mapper.writeValueAsString(Map.of(
                    "error", Map.of("message", "provider-private-secret-detail", "type", "fixture_error"))));
                return;
            }
            var body = new java.util.HashMap<String, Object>();
            body.put("id", "chatcmpl-fixture");
            body.put("object", "chat.completion");
            body.put("created", 1794110000);
            body.put("model", "fixture-model");
            body.put("choices", List.of(Map.of("index", 0, "finish_reason", finishReason,
                "message", Map.of("role", "assistant", "content", content))));
            if (!omitUsage) {
                body.put("usage", Map.of("prompt_tokens", 120, "completion_tokens", 40, "total_tokens", 160));
            }
            respond(exchange, 200, oversized ? "x".repeat(256 * 1024 + 1) : mapper.writeValueAsString(body));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException, InterruptedException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        if (pause) {
            exchange.getResponseBody().write(bytes, 0, 1);
            exchange.getResponseBody().flush();
            requested.countDown();
            release.await(4, TimeUnit.SECONDS);
            exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
        } else {
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        embeddings.release.countDown();
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}

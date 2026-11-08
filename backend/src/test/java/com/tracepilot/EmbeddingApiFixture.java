package com.tracepilot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import tools.jackson.databind.ObjectMapper;

final class EmbeddingApiFixture {
    final AtomicInteger calls = new AtomicInteger();
    final List<String> inputs = new CopyOnWriteArrayList<>();
    volatile boolean unavailable;
    volatile boolean wrongDimension;
    volatile boolean wrongModel;
    volatile boolean duplicateIndex;
    volatile boolean zeroVector;
    volatile boolean pause;
    volatile CountDownLatch requested;
    volatile CountDownLatch release;
    private final ObjectMapper mapper = new ObjectMapper();

    EmbeddingApiFixture() {
        reset();
    }

    void reset() {
        calls.set(0);
        inputs.clear();
        unavailable = false;
        wrongDimension = false;
        wrongModel = false;
        duplicateIndex = false;
        zeroVector = false;
        pause = false;
        requested = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    void handle(HttpExchange exchange) throws IOException, InterruptedException {
        calls.incrementAndGet();
        var request = mapper.readTree(exchange.getRequestBody());
        if (!("Bearer " + AiApiFixture.KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))
            || !"text-embedding-3-small".equals(request.path("model").asString())
            || request.path("dimensions").asInt() != 1536) {
            respond(exchange, 400, "{\"error\":{\"message\":\"Unexpected embedding contract\"}}");
            return;
        }
        if (unavailable) {
            respond(exchange, 503, "{\"error\":{\"message\":\"embedding-provider-private-detail\"}}");
            return;
        }
        var values = new ArrayList<String>();
        var input = request.path("input");
        if (input.isArray()) {
            for (var value : input) {
                values.add(value.asString());
            }
        } else {
            values.add(input.asString());
        }
        inputs.addAll(values);
        var data = new ArrayList<Map<String, Object>>();
        for (int index = values.size() - 1; index >= 0; index--) {
            var vector = new float[wrongDimension ? 1535 : 1536];
            if (!zeroVector) {
                vector[category(values.get(index))] = 1f;
                vector[100] = 0.1f;
            }
            data.add(Map.of("object", "embedding", "index", duplicateIndex ? 0 : index, "embedding", vector));
        }
        respond(exchange, 200, mapper.writeValueAsString(Map.of("object", "list", "data", data,
            "model", wrongModel ? "other-model" : "text-embedding-3-small",
            "usage", Map.of("prompt_tokens", 100, "total_tokens", 100))));
    }

    private int category(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*(eaddrinuse|socket|port |cổng|occupied).*")) {
            return 0;
        }
        if (lower.matches("(?s).*(heap|memory|outofmemory|bộ nhớ|ram).*")) {
            return 1;
        }
        if (lower.matches("(?s).*(postgres|database|connection pool|cơ sở dữ liệu|hikari).*")) {
            return 2;
        }
        if (lower.matches("(?s).*(permission|eacces|access denied|quyền|forbidden).*")) {
            return 3;
        }
        if (lower.matches("(?s).*(http 500|http 200|server error|internal server).*")) {
            return 4;
        }
        if (lower.matches("(?s).*(gradle|dependency|artifact|maven).*")) {
            return 5;
        }
        return 15;
    }

    private void respond(HttpExchange exchange, int status, String content) throws IOException, InterruptedException {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
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
}

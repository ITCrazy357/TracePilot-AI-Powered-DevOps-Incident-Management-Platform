package com.tracepilot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;

final class JobLogApiFixture {
    final AtomicInteger calls = new AtomicInteger();
    volatile String content;
    volatile String redirectOverride;
    volatile boolean unavailable;
    volatile boolean oversized;
    volatile boolean pause;
    volatile boolean authorizationForwarded;
    volatile CountDownLatch requested;
    volatile CountDownLatch release;

    JobLogApiFixture() {
        reset();
    }

    void reset() {
        calls.set(0);
        content = "Compiling sources\nAuthorization: Bearer private-header-value\n"
            + "DATABASE_PASSWORD=\"database-private-value\"\n"
            + "https://user:private-url-value@example.com/repository\n"
            + "ghp_abcdefghijklmnopqrstuvwx\n"
            + GitHubApiFixture.INSTALLATION_TOKEN + "\n"
            + "Error: expected HTTP 200 but got 500\n";
        redirectOverride = null;
        unavailable = false;
        oversized = false;
        pause = false;
        authorizationForwarded = false;
        requested = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    void redirect(HttpExchange exchange, String baseUrl) throws IOException {
        calls.incrementAndGet();
        if (unavailable) {
            byte[] bytes = "provider-error-secret-must-not-persist".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            exchange.getResponseBody().write(bytes);
            return;
        }
        exchange.getResponseHeaders().set("Location",
            redirectOverride == null ? baseUrl + "/download/job.log?sig=private-signed-query" : redirectOverride);
        exchange.sendResponseHeaders(302, -1);
    }

    void download(HttpExchange exchange) throws IOException, InterruptedException {
        authorizationForwarded |= exchange.getRequestHeaders().containsKey("Authorization");
        var bytes = (oversized ? "x".repeat(1024 * 1024 + 1) : content).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        if (pause) {
            exchange.getResponseBody().write(bytes, 0, 1);
            exchange.getResponseBody().flush();
            requested.countDown();
            release.await(15, TimeUnit.SECONDS);
            exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
        } else {
            exchange.getResponseBody().write(bytes);
        }
    }
}

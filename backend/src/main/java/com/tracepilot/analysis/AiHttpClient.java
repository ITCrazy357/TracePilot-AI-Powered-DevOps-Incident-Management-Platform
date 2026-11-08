package com.tracepilot.analysis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponse;
import com.tracepilot.common.LimitedResponseBody;

// This transport supports only the synchronous chat endpoint used by this worker.
public class AiHttpClient implements com.openai.core.http.HttpClient {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final AiProperties properties;

    public AiHttpClient(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public HttpResponse execute(com.openai.core.http.HttpRequest request, RequestOptions options) {
        var expected = properties.baseUrl().toString().replaceAll("/+$", "") + "/chat/completions";
        if (!"POST".equals(request.method().name()) || !expected.equals(request.url())) {
            throw new AnalysisFailure("AI_ENDPOINT_REJECTED", false);
        }
        var body = new ByteArrayOutputStream();
        request.body().writeTo(body);
        var builder = HttpRequest.newBuilder(URI.create(expected)).timeout(properties.timeout())
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        for (var name : request.headers().names()) {
            if (!"Content-Length".equalsIgnoreCase(name) && !"Content-Type".equalsIgnoreCase(name)) {
                for (var value : request.headers().values(name)) {
                    builder.header(name, value);
                }
            }
        }
        var pending = client.sendAsync(builder.build(), info -> new LimitedResponseBody(MAX_RESPONSE_BYTES));
        try {
            var response = pending.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new AnalysisFailure("AI_REDIRECT_REJECTED", false);
            }
            var headers = Headers.builder();
            response.headers().map().forEach(headers::put);
            return new BufferedResponse(response.statusCode(), headers.build(), response.body());
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new AnalysisFailure("AI_TIMEOUT", true);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new AnalysisFailure("AI_INTERRUPTED", true);
        } catch (ExecutionException exception) {
            throw new AnalysisFailure("AI_TRANSPORT_FAILED", true);
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(com.openai.core.http.HttpRequest request, RequestOptions options) {
        return CompletableFuture.failedFuture(new AnalysisFailure("AI_ASYNC_UNSUPPORTED", false));
    }

    @Override
    public void close() {
        client.close();
    }

    private record BufferedResponse(int statusCode, Headers headers, byte[] bytes) implements HttpResponse {
        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {
            // The bounded response is already fully read; no network stream remains open.
        }
    }
}

package com.tracepilot.github;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class GitHubWebhookController {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private final WebhookSignatureVerifier signatureVerifier;
    private final GitHubWebhookService webhookService;

    public GitHubWebhookController(WebhookSignatureVerifier signatureVerifier, GitHubWebhookService webhookService) {
        this.signatureVerifier = signatureVerifier;
        this.webhookService = webhookService;
    }

    @PostMapping(value = "/webhooks/github", consumes = MediaType.APPLICATION_JSON_VALUE)
    public GitHubDtos.WebhookResponse receive(HttpServletRequest request) throws IOException {
        // Bounded reads also protect chunked requests, before JSON parsing allocates objects.
        byte[] rawBody = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (rawBody.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "GitHub webhook exceeds 1 MiB.");
        }
        var hash = signatureVerifier.verifyAndHash(rawBody, request.getHeader("X-Hub-Signature-256"));
        return webhookService.accept(request.getHeader("X-GitHub-Delivery"), request.getHeader("X-GitHub-Event"),
            rawBody, hash);
    }
}

package com.tracepilot.github;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WebhookSignatureVerifier {
    private final GitHubProperties properties;

    public WebhookSignatureVerifier(GitHubProperties properties) {
        this.properties = properties;
    }

    public String verifyAndHash(byte[] rawBody, String signature) {
        properties.requireEnabled();
        if (signature == null || !signature.matches("sha256=[a-fA-F0-9]{64}")) {
            throw invalidSignature();
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            var key = new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            var provided = HexFormat.of().parseHex(signature.substring("sha256=".length()));
            if (!MessageDigest.isEqual(mac.doFinal(rawBody), provided)) {
                throw invalidSignature();
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required webhook cryptography is unavailable.", exception);
        }
    }

    private ResponseStatusException invalidSignature() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid GitHub webhook signature.");
    }
}
